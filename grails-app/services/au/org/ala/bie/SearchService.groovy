package au.org.ala.bie

import au.org.ala.bie.search.IndexDocType
import grails.converters.JSON
import groovy.json.JsonSlurper
import org.apache.solr.client.solrj.util.ClientUtils
import org.gbif.nameparser.NameParser

/**
 * A set of search services for the BIE.
 */
class SearchService {

    def grailsApplication

    /**
     * Retrieve species & subspecies for the supplied taxon which have images.
     *
     * @param taxonID
     * @param start
     * @param rows
     * @return
     */
    def imageSearch(taxonID, start, rows, queryContext){

        def query = "q=*:*"

        if(taxonID){
            //retrieve the taxon rank and then construct the query
            def taxon = lookupTaxon(taxonID)
            if(!taxon){
                return []
            }
            query = "q=*:*&fq=rkid_" + taxon.rank.toLowerCase() + ":\"" +  URLEncoder.encode(taxon.guid, "UTF-8") + "\""
        }

        def additionalParams = "&wt=json&fq=rankID:%5B7000%20TO%20*%5D&fq=imageAvailable:yes"

        if(start){
            additionalParams = additionalParams + "&start=" + start
        }

        if(rows){
            additionalParams = additionalParams + "&rows=" + rows
        }

        if(queryContext){
            additionalParams = additionalParams + queryContext
        }

        def queryUrl = grailsApplication.config.indexLiveBaseUrl + "/select?" + query + additionalParams

        log.debug(queryUrl)
        def queryResponse = new URL(queryUrl).getText("UTF-8")

        def js = new JsonSlurper()

        def json = js.parseText(queryResponse)

        [
                totalRecords:json.response.numFound,
                facetResults: formatFacets(json.facet_counts?.facet_fields?:[]),
                results: formatDocs(json.response.docs, null)
        ]
    }


    /**
     * General search service.
     *
     * @param requestedFacets
     * @return
     */
    def search(q, queryString, requestedFacets) {

        String qf = grailsApplication.config.solr.qf // dismax query fields
        String bq = grailsApplication.config.solr.bq  // dismax boost function
        String defType = grailsApplication.config.solr.defType // query parser type
        String qAlt = grailsApplication.config.solr.qAlt // if no query specified use this query
        String hl = grailsApplication.config.solr.hl // highlighting params (can be multiple)
        def additionalParams = "&qf=${qf}&bq=${bq}&defType=${defType}&q.alt=${qAlt}&hl=${hl}&wt=json&facet=${!requestedFacets.isEmpty()}&facet.mincount=1"

        if (requestedFacets) {
            additionalParams = additionalParams + "&facet.field=" + requestedFacets.join("&facet.field=")
        }

        if (queryString) {
            if (!q) {
                queryString = queryString.replaceFirst("q=", "q=*:*")
            } else if (q.trim() == "*") {
                queryString = queryString.replaceFirst("q=*", "q=*:*")
            }
            // boost query syntax was removed from here. NdR.
        } else {
            queryString = "q=*:*"
        }

        String solrUlr = grailsApplication.config.indexLiveBaseUrl + "/select?" + queryString + additionalParams
        log.debug "solrUlr = ${solrUlr}"
        def queryResponse = new URL(solrUlr).getText("UTF-8")
        def js = new JsonSlurper()
        def json = js.parseText(queryResponse)

        if (json.response.numFound as Integer == 0) {

            try {

                //attempt to parse the name
                def nameParser = new NameParser()
                def parsedName = nameParser.parse(q)
                if (parsedName && parsedName.canonicalName()) {
                    def canonical = parsedName.canonicalName()
                    def sciNameQuery = grailsApplication.config.indexLiveBaseUrl + "/select?q=scientificName:\"" + URLEncoder.encode(canonical, "UTF-8") + "\"" + additionalParams
                    log.debug "sciNameQuery = ${sciNameQuery}"
                    queryResponse = new URL(sciNameQuery).getText("UTF-8")
                    js = new JsonSlurper()
                    json = js.parseText(queryResponse)
                }
            } catch(Exception e){
                //expected behaviour for non scientific name matches
                log.debug "expected behaviour for non scientific name matches: ${e}"
            }
        }

        def queryTitle = q
        def matcher = ( queryTitle =~ /(rkid_)([a-z]{1,})(:)(.*)/ )
        if(matcher.matches()){
            try {
                def rankName = matcher[0][2]
                def guid = matcher[0][4]
                def shortProfile = getShortProfile(guid)
                queryTitle = rankName + " " + shortProfile.scientificName
            } catch (Exception e){

            }
        }

        if(!queryTitle){
            queryTitle = "all records"
        }


        log.debug("search called with q = ${q}, returning ${json.response.numFound}")

        [
            totalRecords: json.response.numFound,
            facetResults: formatFacets(json.facet_counts?.facet_fields ?: []),
            results     : formatDocs(json.response.docs, json.highlighting),
            queryTitle  : queryTitle
        ]
    }

    def getHabitats(){
        def queryUrl = grailsApplication.config.indexLiveBaseUrl + "/select?wt=json&rows=1000&q=idxtype:" + IndexDocType.HABITAT.toString()
        def queryResponse = new URL(queryUrl).getText("UTF-8")
        def js = new JsonSlurper()
        def json = js.parseText(queryResponse)
        def children = []
        def taxa = json.response.docs
        taxa.each { taxon ->
            children << [
                    guid:taxon.guid,
                    parentGuid: taxon.parentGuid,
                    name: taxon.name
            ]
        }
        children
    }

    def getHabitatsIDsByGuid(guid){
        def queryUrl = grailsApplication.config.indexLiveBaseUrl + "/select?wt=json&rows=1&q=idxtype:" + IndexDocType.HABITAT.toString() +
                "&fq=guid:\"" + URLEncoder.encode(guid, 'UTF-8') + "\""

        def queryResponse = new URL(queryUrl).getText("UTF-8")
        def js = new JsonSlurper()
        def json = js.parseText(queryResponse)

        //construct a tree
        def ids = []
        if(json.response.docs){
            def doc = json.response.docs[0]
            ids << doc.name
            ids << getChildHabitatIDs(doc.guid)
        }

        ids.flatten()
    }

    private def getChildHabitatIDs(guid){
        def queryUrl = grailsApplication.config.indexLiveBaseUrl + "/select?wt=json&rows=1000&q=idxtype:" + IndexDocType.HABITAT.toString() +
                "&fq=parentGuid:\"" + URLEncoder.encode(guid, 'UTF-8') + "\""

        def queryResponse = new URL(queryUrl).getText("UTF-8")
        def js = new JsonSlurper()
        def json = js.parseText(queryResponse)

        def ids = []
        //construct a tree
        json.response.docs.each {
            ids << it.name
            ids << getChildHabitatIDs(it.guid)
        }
        ids
    }

    def getHabitatByGuid(guid){
        def queryUrl = grailsApplication.config.indexLiveBaseUrl + "/select?wt=json&rows=1&q=idxtype:" + IndexDocType.HABITAT.toString() +
                "&fq=guid:\"" + URLEncoder.encode(guid, 'UTF-8') + "\""

        def queryResponse = new URL(queryUrl).getText("UTF-8")
        def js = new JsonSlurper()
        def json = js.parseText(queryResponse)

        //construct a tree
        def root = [:]
        if(json.response.docs){
            def doc = json.response.docs[0]
            return [
                    guid:doc.guid,
                    name: doc.name,
                    children: getChildHabitats(doc.guid)
            ]
        }
    }

    private def getChildHabitats(guid){
        def queryUrl = grailsApplication.config.indexLiveBaseUrl + "/select?wt=json&rows=1000&q=idxtype:" + IndexDocType.HABITAT.toString() +
                "&fq=parentGuid:\"" + URLEncoder.encode(guid, 'UTF-8') + "\""

        def queryResponse = new URL(queryUrl).getText("UTF-8")
        def js = new JsonSlurper()
        def json = js.parseText(queryResponse)

        def subTree = []
        //construct a tree
        json.response.docs.each {
            subTree << [
                    guid:it.guid,
                    name: it.name,
                    children: getChildHabitats(it.guid)
            ]
        }
        subTree
    }

    def getHabitatsTree(){
        def queryUrl = grailsApplication.config.indexLiveBaseUrl + "/select?wt=json&rows=1000&q=idxtype:" + IndexDocType.HABITAT.toString()
        def queryResponse = new URL(queryUrl).getText("UTF-8")
        def js = new JsonSlurper()
        def json = js.parseText(queryResponse)

        //construct a tree
        def root = [:]
        json.response.docs.each {
            if(!it.parentGuid){
                root[it.guid] = [
                    guid:it.guid,
                    name: it.name
                ]
            }
        }
        //look for children of the root
        def nodes = root.values()
        nodes.each { addChildren(json.response.docs, it) }
        root
    }

    private def addChildren(docs, node){
        docs.each {
            if(it.parentGuid && node.guid == it.parentGuid){
                if(!node.children){
                    node.children = [:]
                }
                def childNode = [
                        guid:it.guid,
                        name: it.name
                ]

                node.children[it.guid] = childNode
                addChildren(docs, childNode)
            }
        }
    }


    def getChildConcepts(taxonID, queryString){

        def queryUrl = grailsApplication.config.indexLiveBaseUrl + "/select?wt=json&rows=1000&q=parentGuid:\"" + taxonID + "\""

        if(queryString){
            queryUrl = queryUrl + "&" + queryString
        }

        def queryResponse = new URL(queryUrl).getText("UTF-8")
        def js = new JsonSlurper()
        def json = js.parseText(queryResponse)
        def children = []
        def taxa = json.response.docs
        taxa.each { taxon ->
            children << [
                    guid:taxon.guid,
                    parentGuid: taxon.parentGuid,
                    name: taxon.scientificName,
                    nameComplete: taxon.nameComplete ?: taxon.scientificName,
                    nameFormatted: taxon.nameFormatted,
                    author: taxon.scientificNameAuthorship,
                    rank: taxon.rank,
                    rankID:taxon.rankID
            ]
        }
        children.sort { it.name }
    }

    /**
     * Retrieve details of a taxon by taxonID
     *
     * @param taxonID
     * @param useOfflineIndex
     * @return
     */
    private def lookupTaxon(String taxonID, Boolean useOfflineIndex){
        def indexServerUrlPrefix = grailsApplication.config.indexLiveBaseUrl

        if (useOfflineIndex) {
            indexServerUrlPrefix = grailsApplication.config.indexOfflineBaseUrl
        }

        def indexServerUrl = indexServerUrlPrefix+ "/select?wt=json&q=guid:\"" + URLEncoder.encode(taxonID, 'UTF-8') + "\"&fq=idxtype:" + IndexDocType.TAXON.name()
        def queryResponse = new URL(indexServerUrl).getText("UTF-8")
        def js = new JsonSlurper()
        def json = js.parseText(queryResponse)
        json.response.docs[0]
    }

    /**
     * Retrieve details of a taxon by taxonID
     * @param taxonID
     * @return
     */
    private def lookupTaxon(taxonID){
        lookupTaxon(taxonID, false)
    }

    /**
     * Retrieve details of a taxon by common name or scientific name
     * @param taxonID
     * @return
     */
    private def lookupTaxonByName(String taxonName, Boolean useOfflineIndex){
        def indexServerUrlPrefix = grailsApplication.config.indexLiveBaseUrl
        if (useOfflineIndex) {
            indexServerUrlPrefix = grailsApplication.config.indexOfflineBaseUrl
        }
        def solrServerUrl = indexServerUrlPrefix + "/select?wt=json&q=" +URLEncoder.encode(
                "commonNameExact:\"" + taxonName + "\" OR scientificName:\"" + taxonName + "\"",
                "UTF-8"
        )
        def queryResponse = new URL(solrServerUrl).getText("UTF-8")
        def js = new JsonSlurper()
        def json = js.parseText(queryResponse)
        json.response.docs[0]
    }

    private def lookupTaxonByName(taxonName){
        lookupTaxonByName(taxonName, false)
    }

    def getProfileForName(name){

        def additionalParams = "&wt=json"
        def queryString = "q=" + URLEncoder.encode(name, "UTF-8") + "&fq=idxtype:" + IndexDocType.TAXON.name()

        def queryResponse = new URL(grailsApplication.config.indexLiveBaseUrl + "/select?" + queryString + additionalParams).getText("UTF-8")
        def js = new JsonSlurper()
        def json = js.parseText(queryResponse)
        def model = []
        if(json.response.numFound > 0){
            json.response.docs.each { result ->
                model << [
                    "identifier": result.guid,
                    "name": result.scientificName,
                    "acceptedIdentifier": result.acceptedConceptID,
                    "acceptedName": result.acceptedConceptName
                ]
            }
        }
        model
    }

    def getShortProfile(taxonID){
        def taxon = lookupTaxon(taxonID)
        if(!taxon){
            return null
        }
        def classification = extractClassification(taxon)
        def model = [
                taxonID:taxon.guid,
                scientificName: taxon.scientificName,
                scientificNameAuthorship: taxon.scientificNameAuthorship,
                author: taxon.scientificNameAuthorship,
                rank: taxon.rank,
                rankID:taxon.rankID,
                kingdom: classification.kingdom?:"",
                family: classification.family?:""
        ]

        if(taxon.commonName){
            model.put("commonName",  taxon.commonName.first())
        }

        if(taxon.image){
            model.put("thumbnail", grailsApplication.config.imageThumbnailUrl + taxon.image)
            model.put("imageURL", grailsApplication.config.imageLargeUrl + taxon.image)
        }
        model
    }

    def getTaxa(List guidList){

        def postBody = [ q: "guid:(\"" + guidList.join( '","') + "\")", fq: "idxtype:" + + IndexDocType.TAXON.name(), wt: "json" ] // will be url-encoded
        def resp = doPostWithParams(grailsApplication.config.indexLiveBaseUrl +  "/select", postBody)

        //create the docs....
        if(resp.resp.response){

            def matchingTaxa = []

            resp.resp.response.docs.each { doc ->
               def taxon = [
                       guid: doc.guid,
                       name: doc.scientificName,
                       scientificName: doc.scientificName,
                       author: doc.scientificNameAuthorship
               ]
               if(doc.image){
                   taxon.put("thumbnailUrl", grailsApplication.config.imageThumbnailUrl + doc.image)
                   taxon.put("smallImageUrl", grailsApplication.config.imageSmallUrl + doc.image)
                   taxon.put("largeImageUrl", grailsApplication.config.imageLargeUrl + doc.image)
               }
               if(doc.commonName){
                   taxon.put("commonNameSingle", doc.commonName.first())
               }

               matchingTaxa << taxon
            }
            matchingTaxa
        } else {
            resp
        }
    }

    def getTaxon(taxonLookup){

        def taxon = lookupTaxon(taxonLookup)
        if(!taxon){
            taxon = lookupTaxonByName(taxonLookup)
            if(!taxon){
                return null
            }
        }

        //retrieve any synonyms
        def synonymQueryUrl = grailsApplication.config.indexLiveBaseUrl + "/select?wt=json&q=" +
                URLEncoder.encode("acceptedConceptID:\"" + taxon.guid + "\"", "UTF-8") + "&fq=idxtype:" + IndexDocType.TAXON.name()
        def synonymQueryResponse = new URL(synonymQueryUrl).getText("UTF-8")
        def js = new JsonSlurper()
        def synJson = js.parseText(synonymQueryResponse)

        def synonyms = synJson.response.docs

        //retrieve any common names
        def commonQueryUrl = grailsApplication.config.indexLiveBaseUrl + "/select?wt=json&q=" +
                URLEncoder.encode("taxonGuid:\"" + taxon.guid + "\"", "UTF-8") + "&fq=idxtype:" + IndexDocType.COMMON.name()
        def commonQueryResponse = new URL(commonQueryUrl).getText("UTF-8")
        def commonJson = js.parseText(commonQueryResponse)
        def commonNames = commonJson.response.docs


        //retrieve any additional identifiers
        def identifierQueryUrl = grailsApplication.config.indexLiveBaseUrl + "/select?wt=json&q=" +
                URLEncoder.encode("taxonGuid:\"" + taxon.guid + "\"", "UTF-8") + "&fq=idxtype:" + IndexDocType.IDENTIFIER.name()
        def identifierQueryResponse = new URL(identifierQueryUrl).getText("UTF-8")
        def identifierJson = js.parseText(identifierQueryResponse)
        def identifiers = identifierJson.response.docs
        def classification = extractClassification(taxon)

        def model = [
                taxonConcept:[
                        guid: taxon.guid,
                        parentGuid: taxon.parentGuid,
                        nameString: taxon.scientificName,
                        nameComplete: taxon.nameComplete,
                        nameFormatted: taxon.nameFormatted,
                        author: taxon.scientificNameAuthorship,
                        rankString: taxon.rank,
                        nameAuthority: taxon.datasetName ?: grailsApplication.config.defaultNameSourceAttribution,
                        rankID:taxon.rankID,
                        namePublishedIn: taxon.namePublishedIn,
                        namePublishedInYear: taxon.namePublishedInYear,
                        namePublishedInID: taxon.namePublishedInID,
                        infoSourceURL: taxon.source
                ],
                taxonName:[],
                classification:classification,
                synonyms:synonyms.collect { synonym ->
                    [
                            nameString: synonym.scientificName,
                            nameComplete: synonym.nameComplete,
                            nameFormatted: synonym.nameFormatted,
                            nameGuid: synonym.guid,
                            namePublishedIn: synonym.namePublishedIn,
                            namePublishedInYear: synonym.namePublishedInYear,
                            namePublishedInID: synonym.namePublishedInID,
                            nameAuthority: synonym.datasetName ?: grailsApplication.config.synonymSourceAttribution,
                            infoSourceURL: synonym.source
                    ]
                },
                commonNames: commonNames.collect { commonName ->
                    [
                            nameString: commonName.name,
                            status: commonName.status,
                            priority: commonName.priority,
                            language: commonName.language ?: grailsApplication.config.commonNameDefaultLanguage,
                            infoSourceName: commonName.datasetName ?: grailsApplication.config.commonNameSourceAttribution,
                            infoSourceURL: commonName.source
                    ]
                },
                conservationStatuses:[], //TODO need to be indexed from list tool
                extantStatuses: [],
                habitats: [],
                identifiers: identifiers.collect { identifier ->
                    [
                            identifier: identifier.guid,
                            nameString: identifier.name,
                            status: identifier.status,
                            subject: identifier.subject,
                            format: identifier.format,
                            infoSourceName: identifier.datasetName ?: grailsApplication.config.identifierSourceAttribution,
                            infoSourceURL: identifier.source,
                    ]
                }
        ]
        model
    }

    /**
     * Retrieve a classification for the supplied taxonID.
     *
     * @param taxonID
     */
    def getClassification(taxonID){
        def classification = []
        def taxon = retrieveTaxon(taxonID)

        classification.add(0, [
                rank : taxon.rank,
                rankID : taxon.rankID,
                scientificName : taxon.scientificName,
                guid:taxonID
        ])

        //get parents
        def parentGuid = taxon.parentGuid
        def stop = false

        while(parentGuid && !stop){
            taxon = retrieveTaxon(parentGuid)
            if(taxon) {
                classification.add(0, [
                        rank : taxon.rank,
                        rankID : taxon.rankID,
                        scientificName : taxon.scientificName,
                        guid : taxon.guid
                ])
                parentGuid = taxon.parentGuid
            } else {
                stop = true
            }
        }
        classification
    }

    private def formatFacets(facetFields){
        def formatted = []
        facetFields.each { facetName, arrayValues ->
            def facetValues = []
            for (int i =0; i < arrayValues.size(); i+=2){
                facetValues << [label:arrayValues[i], count: arrayValues[i+1], fieldValue:arrayValues[i] ]
            }
            formatted << [
                    fieldName: facetName,
                    fieldResult: facetValues
            ]
        }
        formatted
    }

    private List formatDocs(docs, highlighting){

        def formatted = []

        docs.each {
            if(it.idxtype == IndexDocType.TAXON.name()){

                def commonNameSingle = ""
                def commonNames = ""
                if(it.commonName){
                    commonNameSingle = it.commonName.get(0)
                    commonNames = it.commonName.join(", ")
                }

                Map doc = [
                        "id" : it.id, // needed for highlighting
                        "guid" : it.guid,
                        "linkIdentifier" : it.linkIdentifier,
                        "idxtype": it.idxtype,
                        "name" : it.scientificName,
                        "kingdom" : it.rk_kingdom,
                        "scientificName" : it.scientificName,
                        "author" : it.scientificNameAuthorship,
                        "nameComplete" : it.nameComplete,
                        "nameFormatted" : it.nameFormatted,
                        "taxonomicStatus" : it.taxonomicStatus,
                        "parentGuid" : it.parentGuid,
                        "rank": it.rank,
                        "rankID": it.rankID ?: -1,
                        "commonName" : commonNames,
                        "commonNameSingle" : commonNameSingle,
                        "occurrenceCount" : it.occurrenceCount,
                        "conservationStatus" : it.conservationStatus
                ]

                if(it.acceptedConceptID){
                    doc.put("acceptedConceptID", it.acceptedConceptID)
                    doc.put("guid", it.acceptedConceptID)
                }

                if(it.synonymDescription_s == "synonym"){
                    doc.put("acceptedConceptName", it.acceptedConceptName)
                }

                if(it.image){
                    doc.put("image", it.image)
                }

                //add de-normalised fields
                def map = extractClassification(it)

                doc.putAll(map)

                formatted << doc
            } else {
                Map doc = [
                        id : it.id,
                        guid : it.guid,
                        linkIdentifier : it.linkIdentifier,
                        idxtype: it.idxtype,
                        name : it.name,
                        description : it.description
                ]
                if (it.taxonGuid)
                    doc.put("taxonGuid", it.taxonGuid)
                formatted << doc
            }
        }

        // highlighting should be a LinkedHashMap with key being the 'id' of the matching result
        highlighting.each { k, v ->
            if (v) {
                Map found = formatted.find { it.id == k }
                List snips = []
                v.each { field, snippetList ->
                    snips.addAll(snippetList)
                }
                found.put("highlight", snips.join("<br>"))
            }
        }

        formatted
    }

    private def retrieveTaxon(taxonID){
        def solrServerUrl = grailsApplication.config.indexLiveBaseUrl + "/select?wt=json&q=guid:\"" + URLEncoder.encode(taxonID, 'UTF-8') + "\"&fq=idxtype:" + IndexDocType.TAXON.name()
        def queryResponse = new URL(solrServerUrl).getText("UTF-8")
        def js = new JsonSlurper()
        def json = js.parseText(queryResponse)
        json.response.docs[0]
    }

    private def extractClassification(queryResult) {
        def map = [:]
        if(queryResult){
            queryResult.keySet().each { key ->
                if (key.startsWith("rk_")) {
                    map.put(key.substring(3), queryResult.get(key))
                }
                if (key.startsWith("rkid_")) {
                    map.put(key.substring(5) + "Guid", queryResult.get(key))
                }
            }
        }
        map
    }

    def doPostWithParams(String url, Map params) {
        def conn = null
        def charEncoding = 'utf-8'
        try {
            String query = ""
            boolean first = true
            for (String name : params.keySet()) {
                query += first ? "?" : "&"
                first = false
                query += name.encodeAsURL() + "=" + params.get(name).encodeAsURL()
            }
            log.debug(url + query)
            conn = new URL(url + query).openConnection()
            conn.setRequestMethod("POST")
            conn.setDoOutput(true)
            conn.setRequestProperty("Content-Type", "application/x-www-form-urlencoded");

            OutputStreamWriter wr = new OutputStreamWriter(conn.getOutputStream(), charEncoding)

            wr.flush()
            def resp = conn.inputStream.text
            wr.close()
            return [resp: JSON.parse(resp?:"{}")] // fail over to empty json object if empty response string otherwise JSON.parse fails
        } catch (SocketTimeoutException e) {
            def error = [error: "Timed out calling web service. URL= ${url}."]
            log.error(error, e)
            return error
        } catch (Exception e) {
            def error = [error: "Failed calling web service. ${e.getMessage()} URL= ${url}.",
                         statusCode: conn?.responseCode?:"",
                         detail: conn?.errorStream?.text]
            log.error(error, e)
            return error
        }
    }
}
