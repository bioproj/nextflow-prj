package bioproj

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





}
