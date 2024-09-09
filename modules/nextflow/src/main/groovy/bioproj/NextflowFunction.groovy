package bioproj

import bioproj.events.kafa.KafkaConfig
import bioproj.events.kafa.PublisherTopic
import nextflow.Global
import nextflow.Session
import org.slf4j.Logger
import org.slf4j.LoggerFactory

class NextflowFunction {
    static private Session getSession() { Global.session as Session }
    public static final Logger log = LoggerFactory.getLogger(NextflowFunction)


    static String randomString(int length=9){
        new Random().with {(1..length).collect {(('a'..'z')).join(null)[ nextInt((('a'..'z')).join(null).length())]}.join(null)}
    }

    static void writeMessage(String topic, String message, UUID key){
        KafkaConfig  config = new KafkaConfig( Global.session.config.navigate('kafka') as Map)
        log.info("config.url:{}",config.url)
        log.info("config.group:{}",config.group)
        new PublisherTopic()
            .withUrl(config.url)
            .withGroup(config.group)
            .withTopic(topic)
            .publishMessage([key, message])
    }
    static void writeMessage(String topic, String message,String key){
        KafkaConfig  config = new KafkaConfig( Global.session.config.navigate('kafka') as Map)
        log.info("config.url:{}",config.url)
        log.info("config.group:{}",config.group)
        new PublisherTopic()
            .withUrl(config.url)
            .withGroup(config.group)
            .withTopic(topic)
            .publishMessage([key, message])
    }


}
