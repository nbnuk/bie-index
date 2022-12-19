FROM solr:8.1
USER solr:solr
WORKDIR /var/solr/data
COPY --chown=solr:solr solr.xml solr.xml
COPY --chown=solr:solr zoo.cfg zoo.cfg
#COPY --chown=solr:solr lib /opt/solr/server/solr-webapp/webapp/WEB-INF/lib/
RUN mkdir -p bie bie/conf bie/suggest-infix
COPY --chown=solr:solr conf bie/conf
COPY --chown=solr:solr bie.core.properties bie/core.properties
COPY --chown=solr:solr data bie/data
#RUN chown -R solr:solr bie
RUN mkdir -p bie-offline bie-offline/conf bie-offline/suggest-infix
COPY --chown=solr:solr conf bie-offline/conf
COPY --chown=solr:solr bie-offline.core.properties bie-offline/core.properties
COPY --chown=solr:solr data bie-offline/data
#RUN chown -R solr:solr bie-offline/data
COPY solr.in.sh /etc/default/solr.in.sh

