package bioproj.api

import bioproj.mongo.QueryOp
import com.mongodb.client.MongoClient
import com.mongodb.client.MongoClients
import com.mongodb.client.MongoCollection
import com.mongodb.client.MongoDatabase
import com.mongodb.util.JSON
import groovy.json.JsonOutput
import groovy.json.JsonSlurper
import groovy.transform.TupleConstructor
import groovy.util.logging.Slf4j
import groovyx.gpars.dataflow.DataflowWriteChannel
import nextflow.Channel
import nextflow.Global
import nextflow.Session
import nextflow.trace.TraceRecord
import nextflow.util.SimpleHttpClient
import org.bson.Document

import java.time.Instant
import java.util.concurrent.CompletableFuture
import java.util.stream.Collectors

@Slf4j
class QueryApiHandler {
    private String url
    private String workflowId
    private String authorize
    private DataflowWriteChannel target
    private SimpleHttpClient httpClient
    private Map opts

    QueryApiHandler withWorkflowId(String workflowId){
        this.workflowId = workflowId
        return this
    }
    QueryApiHandler withUrl(String url){
        this.url = url
        return this
    }
    QueryApiHandler withTarget(DataflowWriteChannel target){
        this.target = target
        return this
    }

    QueryApiHandler withOpts(Map opts) {
        this.opts =opts
        this.url = opts.url
        this.authorize = opts.authorize
//        if( opts.batchSize )
//            this.batchSize = opts.batchSize as Integer
//        if( opts.url)
//            this.url =opts.url
//        if( opts.databases)
//            this.databases =opts.databases
//        if( opts.collection)
//            this.collection =opts.collection
//        if(opts.password)
//            this.password = opts.password
//        if(opts.username)
//            this.username = opts.username
//        if(opts.port)
//            this.port = opts.port
//        if(opts.address)
//            this.address = opts.address
        return this
    }


    QueryApiHandler perform(boolean async) {
        this.httpClient = new SimpleHttpClient()
        this.httpClient.setBearerToken(authorize)
        if( async )
            queryAsync()
        else
            queryExec()
        return this
    }

    protected queryAsync() {
        def future = CompletableFuture.runAsync ({ queryExec() })
        future.exceptionally(this.&handlerException)
    }
    protected void queryExec() {

        try {
            final req = makeTasksReq(opts)
            final resp = sendHttpMessage("${url}/${workflowId}", null, 'GET')
            if(resp.code == 200){
                JsonSlurper jsonSlurper = new JsonSlurper()
                def result = jsonSlurper.parseText(resp.getMessage())

                for(item in result.data){
                    def mata = [id:item['analysisNumber'], analysisNumber: item['analysisNumber'],workflowId:item['workflowId'],singleEnd:false,single_end:false]
                    if(item['fastq1'] == item['fastq2']){
                        log.error "fastq1:${item['fastq1']} 与 fastq2:${item['fastq2']}不能相同！"
                        return
                    }
                    def fastq = [item['fastq1'], item['fastq2']]
                    target.bind([mata,fastq])
                }
            }else {
                log.error "访问${url}/${workflowId}报错:${resp.cause}"
            }

        } finally {
            target.bind(Channel.STOP)
        }

//        println("")


//        try{
//            MongoDatabase database = mongoClient.getDatabase(databases);
//            MongoCollection<Document> collections = database.getCollection(collection);
//            Document query = new Document("workflowId",id);
//            println("mongo databases:"+databases)
//            println("mongo collection:"+collection)
//            println("mongo workflowId id:"+id)
//            def  samples = collections.find(query).findAll();
//            List<String> filterCars = new ArrayList<>();
//            def filterSamples = samples.stream().filter(
//                e -> {
//                    boolean found = !filterCars.contains(e['experimentNumber']);
//                    filterCars.add(e['experimentNumber']);
//                    return found;
//                }
//            ).collect(Collectors.toList())
//            if( filterSamples ==null) return
//
//
//
//
//            for(map in filterSamples){
//                def mata = [id:map['analysisNumber'],sampleNumber: map['sampleNumber'],experimentNumber: map['experimentNumber'], analysisNumber: map['analysisNumber'],"workflowId":map['workflowId'],analysisWorkflow:map['analysisWorkflow'],"singleEnd":false,"single_end":false]
//                if(map['fastq1'] == map['fastq2']){
//                    log.error "fastq1:${map['fastq1']} 与 fastq2:${map['fastq2']}不能相同！"
//                    return
//                }
////                def mata = [id:it['experimentNumber'],sampleNumber: it['sampleNumber'],experimentNumber: it['experimentNumber'],analysisNumber: it['analysisNumber'], tenantName: it['tenantName'],species: it['species'],workflowId:it['workflowId'],analysisWorkflow:it['analysisWorkflow'],"singleEnd":false,"single_end":false]
//                def fastq = [map['fastq1'], map['fastq2']]
//                target.bind([mata,fastq])
//            }

////            }
//        }finally{
//            target.bind(Channel.STOP)
//            mongoClient.close();
//        }
    }

    static private void handlerException(Throwable e) {
        final error = e.cause ?: e
        log.error(error.message, error)
        final session = Global.session as Session
        session?.abort(error)
    }

    @TupleConstructor
    static class Response {
        final int code
        final String message
        final String cause
        boolean isError() { code < 200 || code >= 300 }
    }
    protected Map makeTasksReq(Map opts) {
        return new HashMap()
    }

    protected Response sendHttpMessage(String url, Map payload, String method='POST'){

        httpClient.sendHttpMessage(url, payload, method)
        def response = httpClient.getResponse()
        return new Response(httpClient.responseCode, response)

//        int refreshTries=0
//        final currentRefresh = refreshToken ?: env.get('TOWER_REFRESH_TOKEN')
//
//        while ( true ) {
//            // The actual HTTP request
//            final String json = payload != null ? generator.toJson(payload) : null
//            final String debug = json != null ? JsonOutput.prettyPrint(json).indent() : '-'
//            log.trace "HTTP url=$url; payload:\n${debug}\n"
//            try {
//                if( refreshTries==1 ) {
//                    refreshToken(currentRefresh)
//                }
//
//                httpClient.sendHttpMessage(url, json, method)
//                return new Response(httpClient.responseCode, httpClient.getResponse())
//            }
//            catch( ConnectException e ) {
//                String msg = "Unable to connect to Seqera Platform API: ${getHostUrl(url)}"
//                return new Response(0, msg)
//            }
//            catch (IOException e) {
//                int code = httpClient.responseCode
//                if( code == 401 && ++refreshTries==1 && currentRefresh ) {
//                    // when 401 Unauthorized error is returned - only the very first time -
//                    // and a refresh token is available, make another iteration trying
//                    // having refreshed the authorization token (see 'refreshToken' invocation above)
//                    log.trace "Got 401 Unauthorized response ~ tries refreshing auth token"
//                    continue
//                }
//                else {
//                    log.trace("Got HTTP code $code - refreshTries=$refreshTries - currentRefresh=$currentRefresh", e)
//                }
//
//                String msg
//                if( code == 401 ) {
//                    msg = 'Unauthorized Seqera Platform API access -- Make sure you have specified the correct access token'
//                }
//                else {
//                    msg = parseCause(httpClient.response) ?: "Unexpected response for request $url"
//                }
//                return new Response(code, msg, httpClient.response)
//            }
//        }
    }

}
