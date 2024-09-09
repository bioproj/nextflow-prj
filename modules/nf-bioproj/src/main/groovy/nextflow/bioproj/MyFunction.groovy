package nextflow.bioproj

import nextflow.Global

class MyFunction {

    static String randomString111(int length=9){
        new Random().with {(1..length).collect {(('a'..'z')).join(null)[ nextInt((('a'..'z')).join(null).length())]}.join(null)}
    }
}
