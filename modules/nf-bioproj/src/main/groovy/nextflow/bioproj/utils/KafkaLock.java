package nextflow.bioproj.utils;

import groovy.util.logging.Slf4j;

import java.util.Collection;
import java.util.Collections;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.Semaphore;

public class KafkaLock {
    private static final ConcurrentLinkedQueue<String> queue = new ConcurrentLinkedQueue<>();

    private final ConcurrentMap<String, String> map = new ConcurrentHashMap<String, String>();
    public static Boolean lock(String key){
        if(isLock(key))return false;
        return queue.add(key);
    }
    public static Boolean unlock(String key){
        return queue.remove(key);
    }
    public static Boolean isLock(String key){
        return queue.contains(key);
    }

    public static Boolean isEmpty(){
        return queue.isEmpty();
    }
    public static void empty(){
        queue.clear();
    }
    public static Integer size(){
        return queue.size();
    }
}
