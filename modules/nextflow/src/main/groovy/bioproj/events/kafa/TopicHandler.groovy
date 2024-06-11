package bioproj.events.kafa

import groovy.json.JsonSlurper
import groovy.transform.CompileStatic
import groovy.util.logging.Slf4j
import groovyx.gpars.dataflow.DataflowWriteChannel
import nextflow.Channel
import nextflow.Session
import nextflow.bioproj.utils.KafkaLock
import org.apache.kafka.clients.consumer.ConsumerConfig
import org.apache.kafka.clients.consumer.KafkaConsumer
import org.apache.kafka.common.serialization.StringDeserializer

import java.time.Duration

/**
 * A class to listening from a topic
 *
 * @author : jorge <jorge.aguilera@seqera.io>
 *
 */
@Slf4j
@CompileStatic
class TopicHandler {

    private DataflowWriteChannel target
    private String url
    private String group
    private String topic
    private Duration duration
    private boolean listening
    private Session session

    KafkaConsumer<String, String> consumer

    TopicHandler withGroup(String group) {
        this.group = group
        this
    }

    TopicHandler withUrl(String url) {
        this.url = url
        this
    }

    TopicHandler withTopic(String topic) {
        this.topic = topic
        this
    }

    TopicHandler withDuration(Duration duration) {
        this.duration = duration
        this
    }

    TopicHandler withListening(boolean listening){
        this.listening = listening
        this
    }

    TopicHandler withTarget(DataflowWriteChannel channel) {
        this.target = channel
        return this
    }

    TopicHandler withSession(Session session) {
        this.session = session
        return this
    }

    TopicHandler perform() {
        createConsumer()
        if( listening ) {
            runAsync()
        }else{
            consume()
            consume()
            closeConsumer()
            target.bind(Channel.STOP)
        }
        return this
    }

    private KafkaConsumer<String, String> createConsumer(){
        //required as we are running in a custom Plugin ClassLoader
        ClassLoader currentClassLoader = Thread.currentThread().getContextClassLoader()
        Thread.currentThread().setContextClassLoader(null)
        Properties properties = new Properties()
        properties[ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG] = url
        properties[ConsumerConfig.GROUP_ID_CONFIG] = group
        properties[ConsumerConfig.AUTO_OFFSET_RESET_CONFIG] = 'earliest'
        properties[ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG] = StringDeserializer.class.name
        properties[ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG] = StringDeserializer.class.name
        consumer = new KafkaConsumer<>(properties)
        consumer.subscribe(Arrays.asList(topic))
        Thread.currentThread().setContextClassLoader(currentClassLoader)
        consumer
    }

    void closeConsumer(){
        consumer.close()
//        ThreadFactory.instance.shutdownExecutors()
    }

    void consume(){
        try {
            final records = consumer.poll(duration)
            records.each {
//                def values = it.value().split(",").toList()
//                target << [ it.key(), values]

                def map = new JsonSlurper().parseText(it.value())
                def analysisNumber = map['analysisNumber'] as String
                log.info("分析编号: $analysisNumber")
               if( KafkaLock.isLock(analysisNumber)){
                   log.error "$analysisNumber 正在运行中！！！！！！！！！"
                   return
               }
                KafkaLock.lock(analysisNumber)

                def mata = [id:map['analysisNumber'],sampleNumber: map['sampleNumber'],experimentNumber: map['experimentNumber'], analysisNumber: map['analysisNumber'],"workflowId":map['workflowId'],"singleEnd":false,"single_end":false]
                if(map['fastq1'] == map['fastq2']){
                    log.error "fastq1:${map['fastq1']} 与 fastq2:${map['fastq2']}不能相同！"
                    return
                }
//                def mata = [id: map['number'],number: map['number'], sampleType: map['sampleType'],userName: map['userName'],"single_end":false]
                def fastq = [map['fastq1'], map['fastq2']]
                target.bind([mata,fastq])
            }
        }catch(Exception e){
            log.error "Exception reading kafka topic $topic",e
        }
    }

    void runAsync(){
        final executor = ThreadFactory.instance.createExecutor(topic)
        executor.submit({
            try {
                while(!Thread.interrupted()) {
                    consume()
                    sleep 500
                }
                log.trace "Closing $topic kafka thread"
            }catch(Exception e){
                log.error "Exception reading kafka topic $topic",e
            }finally{
                closeConsumer()
                ThreadFactory.instance.shutdownExecutors()
            }
        })
    }

}
