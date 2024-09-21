package bioproj.trace

import bioproj.NextflowFunction
import bioproj.events.kafa.KafkaConfig
import bioproj.events.kafa.PublisherTopic
import groovy.json.JsonGenerator
import groovy.json.JsonOutput
import groovy.json.JsonSlurper
import groovy.transform.ToString
import groovy.transform.TupleConstructor
import groovy.util.logging.Slf4j
import nextflow.Global
import nextflow.Session
import nextflow.processor.TaskHandler
import nextflow.processor.TaskId
import nextflow.processor.TaskProcessor
import nextflow.trace.ResourcesAggregator
import nextflow.trace.TraceObserver
import nextflow.trace.TraceRecord
import nextflow.util.Duration
import nextflow.util.SimpleHttpClient
import org.apache.kafka.clients.producer.KafkaProducer
import org.apache.kafka.clients.producer.ProducerRecord

import java.nio.file.Path
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneId
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

@Slf4j
class BioprojKafkaObserver implements TraceObserver{
    static final public String DEF_ENDPOINT_URL = 'https://api.tower.nf'
    private String endpoint
    static private final Duration REQUEST_INTERVAL = Duration.of('1 sec')
    static private final Duration ALIVE_INTERVAL = Duration.of('1 min')

    private String refreshToken
    private Session session
    private String workflowId
    private Thread sender
    private boolean terminated
    static private final int TASKS_PER_REQUEST = 100
    private Duration requestInterval = REQUEST_INTERVAL
    private Duration aliveInterval = ALIVE_INTERVAL
    protected Map<String,String> env = System.getenv()
    private JsonGenerator generator
    private String workspaceId
    private boolean towerLaunch
    private ResourcesAggregator aggregator
    private LinkedHashSet<String> processNames = new LinkedHashSet<>(20)
    private Map<String,Integer> schema = Collections.emptyMap()
    private BioprojReports reports
    private KafkaProducer<String,String> producer
    private String topic

    void setWorkspaceId(String workspaceId) {
        this.workspaceId = workspaceId
    }

    protected Map makeCreateReq(Session session) {
        def result = new HashMap(5)
        result.sessionId = session.uniqueId.toString()
        result.runName = session.runName
        result.projectName = session.workflowMetadata.projectName
        result.repository = session.workflowMetadata.repository
        result.workflowId = workflowId //env.get('TOWER_WORKFLOW_ID')
        result.instant = Instant.now().toEpochMilli()
        this.towerLaunch = result.workflowId != null
        return result
    }

    protected String mapToString(def obj) {
        if( obj == null )
            return null
        if( obj instanceof CharSequence )
            return obj.toString()
        if( obj instanceof Map ) {
            def map = obj as Map
            return map.collect { k,v -> "$k:$v" }.join(',')
        }
        throw new IllegalArgumentException("Illegal container attribut type: ${obj.getClass().getName()} = ${obj}" )
    }
    protected Map makeBeginReq(Session session) {
        def workflow = session.getWorkflowMetadata().toMap()
        workflow.params = session.getParams()
        workflow.id = workflowId
        workflow.remove('stats')

        // render as a string
        workflow.container = mapToString(workflow.container)
        workflow.configText = session.resolvedConfig
        // extra metadata
//        workflow.operationId = getOperationId()
//        workflow.logFile = getLogFile()
//        workflow.outFile = getOutFile()

        def result = new LinkedHashMap(5)
        result.workflow = workflow
        result.processNames = new ArrayList(processNames)
        result.towerLaunch = towerLaunch
        result.instant = Instant.now().toEpochMilli()
        return result
    }
    protected List getMetricsList() {
        return aggregator.computeSummaryList()
    }
    protected Map makeCompleteReq(Session session) {
        def workflow = session.getWorkflowMetadata().toMap()
        workflow.params = session.getParams()
        workflow.id = workflowId
        // render as a string
        workflow.container = mapToString(workflow.container)
        workflow.configText = session.resolvedConfig
        // extra metadata
//        workflow.operationId = getOperationId()
//        workflow.logFile = getLogFile()
//        workflow.outFile = getOutFile()

        def result = new LinkedHashMap(5)
        result.workflow = workflow
        result.metrics = getMetricsList()
        result.progress = getWorkflowProgress(false)
        result.instant = Instant.now().toEpochMilli()
        return result
    }
    protected Map makeHeartbeatReq() {
        def result = new HashMap(1)
        result.progress = getWorkflowProgress(true)
        result.instant = Instant.now().toEpochMilli()
        return result
    }
    protected WorkflowProgress getWorkflowProgress(boolean quick) {
        def stats = quick ? session.getStatsObserver().getQuickStats() : session.getStatsObserver().getStats()
        new WorkflowProgress(stats)
    }
    protected String underscoreToCamelCase(String str) {
        if( !str.contains('_') )
            return str

        final words = str.tokenize('_')
        def result = words[0]
        for( int i=1; i<words.size(); i++ )
            result+=words[i].capitalize()

        return result
    }
    static protected Object fixTaskField(String name, value) {
        if( TraceRecord.FIELDS[name] == 'date' )
            return value ? OffsetDateTime.ofInstant(Instant.ofEpochMilli(value as long), ZoneId.systemDefault()) : null
        else
            return value
    }
    protected Map makeTaskMap0(TraceRecord trace) {
        Map<String,?> record = new LinkedHashMap<>(trace.store.size())
        for( Map.Entry<String,Object> entry : trace.store.entrySet() ) {
            def name = entry.key
            // remove '%' char from field prefix
            if( name.startsWith('%') )
                name = 'p' + name.substring(1)
            // normalise to camelCase
            name = underscoreToCamelCase(name)
            // put the value
            record.put(name, fixTaskField(name,entry.value))
        }

        // prevent invalid tag data
        if( record.tag!=null && !(record.tag instanceof CharSequence)) {
            final msg = "Invalid tag value for process: ${record.process} -- A string is expected instead of type: ${record.tag.getClass().getName()}; offending value=${record.tag}"
            log.warn1(msg, cacheKey: record.process)
            record.tag = null
        }

        // add transient fields
        record.executor = trace.getExecutorName()
        record.cloudZone = trace.getMachineInfo()?.zone
        record.machineType = trace.getMachineInfo()?.type
        record.priceModel = trace.getMachineInfo()?.priceModel?.toString()

        return record
    }

    protected Map makeTasksReq(Collection<TraceRecord> tasks) {

        def payload = new ArrayList(tasks.size())
        for( TraceRecord rec : tasks ) {
            payload << makeTaskMap0(rec)
        }

        final result = new LinkedHashMap(5)
        result.put('tasks', payload)
        result.put('progress', getWorkflowProgress(true))
        result.instant = Instant.now().toEpochMilli()
        return result
    }

    @ToString(includeNames = true)
    static class ProcessEvent {
        TraceRecord trace
        boolean completed
    }
    private LinkedBlockingQueue<ProcessEvent> events = new LinkedBlockingQueue()
    /**
     * Check the URL and create an HttpPost() object. If a invalid i.e. protocol is used,
     * the constructor will raise an exception.X
     *
     * The RegEx was taken and adapted from http://urlregex.com
     *
     * @param url String with target URL
     * @return The requested url or the default url, if invalid
     */
    protected String checkUrl(String url){
        if( url =~ "^(https|http)://[-a-zA-Z0-9+&@#/%?=~_|!:,.;]*[-a-zA-Z0-9+&@#/%=~_|]" ) {
            while( url.endsWith('/') )
                url = url[0..-2]
            return url
        }
        throw new IllegalArgumentException("Only http and https are supported -- The given URL was: ${url}")
    }



    protected Map<String,Integer> loadSchema() {
        final props = new Properties()
        props.load(this.getClass().getResourceAsStream('/tower-schema.properties'))
        final result = new HashMap<String,Integer>(props.size())
        for( String key : props.keySet() ) {
            final value = props.getProperty(key)
            result.put( key, value ? value as Integer : null )
        }
        return result
    }






    BioprojKafkaObserver(Session session, String endpoint){
        this.session = session
        this.endpoint = checkUrl(endpoint)
        this.schema = loadSchema()
        this.generator = BioprojJsonGenerator.create(schema)

        KafkaConfig config = new KafkaConfig( Global.session.config.navigate('kafka') as Map)
//        log.info("config.url:{}",config.url)
//        log.info("config.group:{}",config.group)
        this.producer =    new PublisherTopic()
            .withUrl(config.url)
            .withGroup(config.group)
            .createProducer()
        this.topic = "nextflow-trace"
        this.reports = new BioprojReports(session,producer,topic)

    }
    void publishMessage(Object message){
//        KafkaProducer<String,String> producer = createProducer()
        ProducerRecord<String,String> record
        if( message instanceof List) {
            def list = message as List
            record = new ProducerRecord<>(topic, list[0].toString(), list[1].toString())
        }else {
            record = new ProducerRecord<>(topic, message.toString())
        }
        producer.send(record)
    }
    void writeMessage(String message,String key){
        publishMessage([key, message])
    }

    /**
     * @see nextflow.script.ScriptRunner#execute()
     * @see Session#start()
     * @see Session#notifyFlowCreate()
     * **/
    @Override
    void onFlowCreate(Session session) {
        this.httpClient = new SimpleHttpClient()
        this.session = session
        this.aggregator = new ResourcesAggregator(session)
        this.workflowId =  env.get('NXF_WORKFLOW_ID')
        final req = makeCreateReq(session)
        sendEventMessage("W-"+workflowId,req)
        reports.flowCreate(workflowId)


//        final resp = sendHttpMessage(urlTraceCreate, req, 'POST')
//        if( resp.error ) {
//            log.debug """\
//                Unexpected HTTP response
//                - endpoint    : $urlTraceCreate
//                - status code : $resp.code
//                - response msg: $resp.cause
//                """.stripIndent()
//            throw new AbortOperationException(resp.message)
//        }

//        println "Okay, let's onFlowCreate!"


    }

    /**
     * @see nextflow.processor.TaskProcessor#run()
     * @see Session#notifyProcessCreate()
     * **/
    @Override
    void onProcessCreate(TaskProcessor process){
        log.trace "Creating process ${process.name}"
        if( !processNames.add(process.name) )
            throw new IllegalStateException("Process name `${process.name}` already used")
    }


    /**
     * @see Session#notifyFlowBegin()
     * **/
    @Override
    void onFlowBegin() {
        final req = makeBeginReq(session)
        sendEventMessage("P-"+workflowId,req)

//        final resp = sendHttpMessage(urlTraceBegin, req, 'PUT')
//
//        if( resp.error ) {
//            log.debug """\
//                Unexpected HTTP response
//                - endpoint    : $urlTraceBegin
//                - status code : $resp.code
//                - response msg: $resp.cause
//                """.stripIndent()
//            throw new AbortOperationException(resp.message)
//        }

        this.sender = Thread.start('Tower-thread', this.&sendTasks0)
        reports.start()

        println "Okay, let's begin!"
    }

    @Override
    void onFlowComplete() {
        // submit the record
        events << new ProcessEvent(completed: true)
        // wait the submission of pending events
        sender.join()
        reports.flowComplete()

        println ">>>>>>>>>>>>>>>>>> bioproj onFlowComplete!"
        // notify the workflow completion
        terminated = true
        final req = makeCompleteReq(session)
        sendEventMessage("C-"+workflowId,req)


        producer.flush()
        producer.close()
    }



    @Override
    void onProcessPending(TaskHandler handler, TraceRecord trace) {
        events << new ProcessEvent(trace: trace)
    }
    @Override
    void onProcessSubmit(TaskHandler handler, TraceRecord trace) {
        events << new ProcessEvent(trace: trace)
    }

    @Override
    void onProcessStart(TaskHandler handler, TraceRecord trace) {
        events << new ProcessEvent(trace: trace)
    }

    @Override
    void onProcessComplete(TaskHandler handler, TraceRecord trace) {
        events << new ProcessEvent(trace: trace)

        synchronized (this) {
            aggregator.aggregate(trace)
        }

    }

    @Override
    void onProcessCached(TaskHandler handler, TraceRecord trace) {
        if( trace == null )
            return

        // add the cached task event
        events << new ProcessEvent(trace: trace)

        synchronized (this) {
            aggregator.aggregate(trace)
        }
    }

    @Override
    void onFlowError(TaskHandler handler, TraceRecord trace) {
//        println "Uh oh, something went wrong..."
        events << new ProcessEvent(trace: trace)
    }

    @Override
    void onFilePublish(Path destination, Path source) {
        println "I published a file! It's located at ${destination.toUriString()}"
        final result = reports.filePublish(destination)

    }


    protected void sendTasks0(dummy) {


        final tasks = new HashMap<TaskId, TraceRecord>(TASKS_PER_REQUEST)
        boolean complete = false
        long previous = System.currentTimeMillis()
        final long period = requestInterval.millis
        final long delay = period / 10 as long

        while( !complete ) {
            // 表示从队列中获取下一个事件，如果队列为空，则等待指定的时间（delay）来获取事件，时间单位为毫秒
            final ProcessEvent ev = events.poll(delay, TimeUnit.MILLISECONDS)
            // reconcile task events ie. send out only the last event
            if( ev ) {
                log.trace "bioproj event=$ev"
                if( ev.trace )
                    tasks[ev.trace.taskId] = ev.trace
                if( ev.completed )
                    complete = true
            }

            // check if there's something to send
            final now = System.currentTimeMillis()
            final delta = now -previous

            if( !tasks ) {
                if( delta > aliveInterval.millis ) {
                    final req = makeHeartbeatReq()
//                    def json = JsonOutput.toJson(req)
//                    NextflowFunction.writeMessage("nextflow-trace",json,"test");
//                    final resp = sendHttpMessage(urlTraceHeartbeat, req, 'PUT')
//                    logHttpResponse(urlTraceHeartbeat, resp)
                    previous = now
                }
                continue
            }

            if( delta > period || tasks.size() >= TASKS_PER_REQUEST || complete ) {
                // send
                final req = makeTasksReq(tasks.values())
//                def json = JsonOutput.toJson(req)
                def json = generator.toJson(req)
//                if(complete){
//                    NextflowFunction.writeMessage("nextflow-trace",json,"C-"+workflowId);
//
//                }else {
               writeMessage(json,"T-"+workflowId);

//                }
//                final resp = sendHttpMessage(urlTraceProgress, req, 'PUT')
//                logHttpResponse(urlTraceProgress, resp)

                // clean up for next iteration
                previous = now
                tasks.clear()
            }
        }
    }






    protected void sendEventMessage(String key, Map payload){
        final String json = payload != null ? generator.toJson(payload) : null
//        Thread.start('Tower-thread1', {
//
//            writeMessage(json,key);
//        })
        writeMessage(json,key);
    }









    private SimpleHttpClient httpClient



    protected String getUrlTraceProgress() {
        def result = "$endpoint/trace/$workflowId/progress"
        if( workspaceId )
            result += "?workspaceId=$workspaceId"
        return result
    }


    protected String getUrlTraceCreate() {
        def result = this.endpoint + '/trace/create'
        if( workspaceId )
            result += "?workspaceId=$workspaceId"
        return result
    }
    protected String getUrlTraceHeartbeat() {
        def result = "$endpoint/trace/$workflowId/heartbeat"
        if( workspaceId )
            result += "?workspaceId=$workspaceId"
        return result
    }

    protected String getHostUrl(String endpoint) {
        def url = new URL(endpoint)
        return "${url.protocol}://${url.authority}"
    }
    protected String getUrlTraceBegin() {
        def result = "$endpoint/trace/$workflowId/begin"
        if( workspaceId )
            result += "?workspaceId=$workspaceId"
        return result
    }

    protected String parseCause(String cause) {
        if( !cause )
            return null
        try {
            def map = (Map)new JsonSlurper().parseText(cause)
            return map.message
        }
        catch ( Exception ) {
            log.debug "Unable to parse error cause as JSON object: $cause"
            return cause
        }
    }
    @TupleConstructor
    static class Response {
        final int code
        final String message
        final String cause
        boolean isError() { code < 200 || code >= 300 }
    }
    protected void refreshToken(String refresh) {
        log.debug "Token refresh request >> $refresh"
        final url = "$endpoint/oauth/access_token"
        httpClient.sendHttpMessage(
            url,
            method: 'POST',
            contentType: "application/x-www-form-urlencoded",
            body: "grant_type=refresh_token&refresh_token=${URLEncoder.encode(refresh, 'UTF-8')}" )

        final authCookie = httpClient.getCookie('JWT')
        final refreshCookie = httpClient.getCookie('JWT_REFRESH_TOKEN')

        // set the new bearer token
        if( authCookie?.value ) {
            log.trace "Updating http client bearer token=$authCookie.value"
            httpClient.setBearerToken(authCookie.value)
        }
        else {
            log.warn "Missing JWT cookie from refresh token response ~ $authCookie"
        }

        // set the new refresh token
        if( refreshCookie?.value ) {
            log.trace "Updating http client refresh token=$refreshCookie.value"
            refreshToken = refreshCookie.value
        }
        else {
            log.warn "Missing JWT_REFRESH_TOKEN cookie from refresh token response ~ $refreshCookie"
        }
    }
    /**
     * Little helper method that sends a HTTP POST message as JSON with
     * the current run status, ISO 8601 UTC timestamp, run name and the TraceRecord
     * object, if present.
     * @param event The current run status. One of {'started', 'process_submit', 'process_start',
     * 'process_complete', 'error', 'completed'}
     * @param payload An additional object to send. Must be of type TraceRecord or Manifest
     */
    protected Response sendHttpMessage(String url, Map payload, String method='POST'){

        int refreshTries=0
        final currentRefresh = refreshToken ?: env.get('TOWER_REFRESH_TOKEN')

        while ( true ) {
            // The actual HTTP request
            final String json = payload != null ? generator.toJson(payload) : null
            final String debug = json != null ? JsonOutput.prettyPrint(json).indent() : '-'
            log.trace "HTTP url=$url; payload:\n${debug}\n"
            try {
                if( refreshTries==1 ) {
                    refreshToken(currentRefresh)
                }

                httpClient.sendHttpMessage(url, json, method)
                return new Response(httpClient.responseCode, httpClient.getResponse())
            }
            catch( ConnectException e ) {
                String msg = "Unable to connect to Tower API: ${getHostUrl(url)}"
                return new Response(0, msg)
            }
            catch (IOException e) {
                int code = httpClient.responseCode
                if( code == 401 && ++refreshTries==1 && currentRefresh ) {
                    // when 401 Unauthorized error is returned - only the very first time -
                    // and a refresh token is available, make another iteration trying
                    // having refreshed the authorization token (see 'refreshToken' invocation above)
                    log.trace "Got 401 Unauthorized response ~ tries refreshing auth token"
                    continue
                }
                else {
                    log.trace("Got error $code - refreshTries=$refreshTries - currentRefresh=$currentRefresh")
                }

                String msg
                if( code == 401 ) {
                    msg = 'Unauthorized Tower access -- Make sure you have specified the correct access token'
                }
                else {
                    msg = parseCause(httpClient.response) ?: "Unexpected response for request $url"
                }
                return new Response(code, msg, httpClient.response)
            }
        }
    }

    /**
     * Little helper function that can be called for logging upon an incoming HTTP response
     */
    protected void logHttpResponse(String url, Response resp){
        if (resp.code >= 200 && resp.code < 300) {
            log.trace "Successfully send message to ${url} -- received status code ${resp.code}"
        }
        else {
            def cause = parseCause(resp.cause)
            def msg = """\
                Unexpected HTTP response.
                Failed to send message to ${endpoint} -- received 
                - status code : $resp.code
                - response msg: $resp.message
                """.stripIndent()
            // append separately otherwise formatting get broken
            msg += "- error cause : ${cause ?: '-'}"
            log.warn(msg)
        }
    }
}
