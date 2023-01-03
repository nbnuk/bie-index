package bie.index

import grails.boot.GrailsApp
import grails.boot.config.GrailsAutoConfiguration
import grails.plugins.metadata.PluginSource
import org.springframework.boot.autoconfigure.EnableAutoConfiguration
import org.springframework.boot.autoconfigure.solr.SolrAutoConfiguration

@PluginSource
@EnableAutoConfiguration(exclude = [SolrAutoConfiguration])
class Application extends GrailsAutoConfiguration {
    static void main(String[] args) {
//        GrailsApp.run(Application, args)
    }
}