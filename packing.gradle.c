configurations {
    capsule
    defaultCfg.extendsFrom api 
    //provided
    console.extendsFrom defaultCfg
    ga4gh.extendsFrom defaultCfg
    google.extendsFrom defaultCfg
    amazon.extendsFrom defaultCfg
    azure.extendsFrom defaultCfg
    legacy.extendsFrom defaultCfg
    tower.extendsFrom defaultCfg
    wave.extendsFrom defaultCfg
    trace.extendsFrom defaultCfg
}

dependencies {
    api project(':nextflow')
    // include Ivy at runtime in order to have Grape @Grab work correctly
    defaultCfg "org.apache.ivy:ivy:2.5.2"
    // default cfg = runtime + httpfs + amazon + tower client + wave client
    defaultCfg project(':nf-httpfs')
    // Capsule manages the fat jar building process
    capsule 'io.nextflow:capsule:1.1.1'
    capsule 'io.nextflow:capsule-maven:1.0.3.2'
    console project(':plugins:nf-console')
    ga4gh   project(':plugins:nf-ga4gh')
    // google  project(':plugins:nf-google')
    amazon  project(':plugins:nf-amazon')
    azure   project(':plugins:nf-azure')
    tower   project(':plugins:nf-tower')
    // wave    project(':plugins:nf-wave')
    trace    project(':plugins:nf-trace')
}


ext.mainClassName = 'nextflow.cli.Launcher'
ext.homeDir = System.properties['user.home']
ext.nextflowDir = "$homeDir/.nextflow/framework/$version"
ext.releaseDir = "$buildDir/releases"
ext.s3CmdOpts="--acl public-read --storage-class STANDARD --region eu-west-1"

protected error(String message) {
    logger.error message
    throw new StopExecutionException(message)
}

protected checkVersionExits(String version) {
    if(version.endsWith('-SNAPSHOT'))
        return

    def cmd = "AWS_ACCESS_KEY_ID=${System.env.NXF_AWS_ACCESS} AWS_SECRET_ACCESS_KEY=${System.env.NXF_AWS_SECRET} aws s3 ls s3://www2.nextflow.io/releases/v$version/nextflow"
    def status=['bash','-c', cmd].execute().waitFor()
    if( status == 0 )
        error("STOP!! Version $version already deployed!")
}

protected resolveDeps( String configName, String... x ) {

    final deps = [] as Set
    final config = configurations.getByName(configName)
    //final root = config.getIncoming().getResolutionResult().root.moduleVersion.toString()
    config.getResolvedConfiguration().getResolvedArtifacts().each{ deps << coordinates(it) }

    if( x )
        deps.addAll(x as List)

    logger.info ">> Dependencies for configuration: $configName"
    deps.sort().each { logger.info " - $it" }

    // make sure there aren't any version conflict
    def conflicts = deps.countBy {  it.tokenize(":")[0..1].join(':') }.findAll { k,v -> v>1 }
    if( conflicts ) {
        def err = "There are multiple versions for the following lib(s):"
        conflicts.each { name, count -> err += "\n- $name: " + deps.findAll{ it.startsWith(name) }.collect{ it.tokenize(':')[2] } }
        throw new IllegalStateException(err)
    }
    
    //println "** $configName\n${deps.sort().collect{"- $it"}.join('\n')}\n"

    //println ">> Config: $configName \n${deps.sort().join('\n')}\n\n"

    return deps.collect{ "$it(*:*)" }.join(' ')
}

protected coordinates( it ) {
    if( it instanceof Dependency )
        return "${it.group}:${it.name}:${it.version}".toString()

    if( it instanceof ResolvedArtifact ) {
        def result = it.moduleVersion.id.toString()
        if( it.classifier ) result += ":${it.classifier}"
        return result
    }

    throw new IllegalArgumentException("Not a valid dependency object [${it?.class?.name}]: $it")
}

/*
 * Default nextflow package. It contains the capsule loader
 */
task packOne(type: Jar) {
    dependsOn configurations.capsule, configurations.defaultCfg
    archiveFileName = "nextflow-${version}-one.jar"

    from (configurations.capsule.collect { zipTree(it) })

    // main manifest attributes
    def deps = resolveDeps('defaultCfg')

    manifest.attributes(
                'Main-Class'        : 'NextflowLoader',
                'Application-Name'  : 'nextflow',
                'Application-Class' : mainClassName,
                'Application-Version': version,
                'Min-Java-Version'  : '1.8.0',
                'Caplets'           : 'MavenCapsule',
                'Dependencies'      : deps
    )

    // enable snapshot dependencies lookup
    if( version.endsWith('-SNAPSHOT') ) {
        manifest.attributes 'Allow-Snapshots': true
        manifest.attributes 'Repositories': 'local https://oss.sonatype.org/content/repositories/snapshots central seqera'
    }
    else {
        manifest.attributes 'Repositories': 'central seqera'
    }

    doLast {
        ant.copy(file: "$buildDir/libs/nextflow-${version}-one.jar", todir: releaseDir, overwrite: true)
        ant.copy(file: "$buildDir/libs/nextflow-${version}-one.jar", todir: nextflowDir, overwrite: true)
        println "\n+ Nextflow package `ONE` copied to: $releaseDir"
    }
}

task packAll(type: Jar) {
    dependsOn configurations.capsule, configurations.defaultCfg
    archiveFileName = "nextflow-${version}-all.jar"

    from jar // embed our application jar
    from (configurations.amazon + configurations.google + configurations.tower + configurations.wave+configurations.trace)
    from (configurations.capsule.collect { zipTree(it) })
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE
    
    manifest.attributes( 'Main-Class'        : 'NextflowLoader',
                          'Application-Name'  : 'nextflow-all',
                          'Application-Class' : mainClassName,
                          'Application-Version': version,
                          'Min-Java-Version'  : '1.8.0'
                          )

    manifest.attributes('Main-Class': 'NextflowLoader', 'amazon')
    manifest.attributes('Main-Class': 'NextflowLoader', 'google')

    if( project.hasProperty('GA4GH') ) {
        println "The build will include GA4GH dependencies."
        from(configurations.ga4gh)
        manifest.attributes('Main-Class': 'CapsuleLoader', 'ga4gh')
    }

    doLast {
        file(releaseDir).mkdir()
        // cleanup
        def source = file("$buildDir/libs/nextflow-${version}-all.jar")
        def target = file("$releaseDir/nextflow-${version}-all"); target.delete()
        // append the big jar
        target.withOutputStream {
            it << file('nextflow').text.replaceAll(/NXF_PACK\=.*/, 'NXF_PACK=all')
            it << new FileInputStream(source)
        }
        // execute permission
        "chmod +x $target".execute()
        // done
        println "+ Nextflow package `ALL` copied to: $releaseDir\n"
    }
}

task packCore(type: Jar) {
    dependsOn configurations.capsule, configurations.defaultCfg
    archiveFileName = "nextflow-${version}-core.jar"

    from jar // embed our application jar
    from (configurations.defaultCfg)
    from (configurations.capsule.collect { zipTree(it) })
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE

    manifest.attributes( 'Main-Class'        : 'NextflowLoader',
            'Application-Name'  : 'nextflow-core',
            'Application-Class' : mainClassName,
            'Application-Version': version,
            'Min-Java-Version'  : '1.8.0'
    )

    doLast {
        file(releaseDir).mkdir()
        // cleanup
        def source = file("$buildDir/libs/nextflow-${version}-core.jar")
        def target = file("$releaseDir/nextflow-${version}-core"); target.delete()
        // append the big jar
        target.withOutputStream {
            it << file('nextflow').text.replaceAll(/NXF_PACK\=.*/, 'NXF_PACK=all')
            it << new FileInputStream(source)
        }
        // execute permission
        "chmod +x $target".execute()
        // done
        println "+ Nextflow package `CORE` copied to: $releaseDir\n"
    }
}


/*
 * Compile and pack all packages
 */
task pack( dependsOn: [packOne, packAll]) {

}


task deploy( type: Exec, dependsOn: [clean, compile, pack]) {

    def temp = File.createTempFile('upload',null)
    temp.deleteOnExit()
    def files = []

    doFirst {
        checkVersionExits(version)
        
        def path = new File(releaseDir)
        if( !path.exists() ) error("Releases path does not exist: $path")
        path.eachFile {
            if( it.name.startsWith("nextflow-$version"))
                files << it
        }

        if( !files ) error("Can't find any file to upload -- Check path: $path")
        files << file('nextflow').absoluteFile
        files << file('nextflow.sha1').absoluteFile
        files << file('nextflow.sha256').absoluteFile
        files << file('nextflow.md5').absoluteFile

        println "Uploading artifacts: "
        files.each { println "- $it"}

        def script = []
        script << "export AWS_ACCESS_KEY_ID=${System.env.NXF_AWS_ACCESS}"
        script << "export AWS_SECRET_ACCESS_KEY=${System.env.NXF_AWS_SECRET}"
        script.addAll( files.collect { "aws s3 cp ${it} s3://www2.nextflow.io/releases/v${version}/${it.name} ${s3CmdOpts}"})

        temp.text = script.join('\n')
    }

    commandLine 'bash', '-e', temp.absolutePath
}

task installLauncher(type: Copy, dependsOn: ['pack']) {
    from "$releaseDir/nextflow-$version-one.jar"
    into "$homeDir/.nextflow/framework/$version/"
}

/*
 * build, tag and publish a and new docker packaged nextflow release
 */
task dockerImage(type: Exec) {

    def temp = File.createTempFile('upload',null)
    temp.deleteOnExit()
    temp.text =  """\
    grep $version nextflow
    cp nextflow docker/nextflow
    cd docker
    make release version=$version
    """.stripIndent()

    commandLine 'bash', '-e', temp.absolutePath
}

/*
 * Build a docker container image with the current snapshot
 * DO NOT CALL IT DIRECTLY! Use the `make dockerPack` command instead
 */
task dockerPack(type: Exec, dependsOn: ['packOne']) {
    println '11111111111111111111111111111111111111'
    def source = new File("$releaseDir/nextflow-$version-one.jar")
    def target = new File("$buildDir/docker/.nextflow/framework/$version/")
    println target
    def dockerBase = new File("$buildDir/docker")
    if( dockerBase.exists() ) dockerBase.deleteDir()
    dockerBase.mkdirs()
    def dockerFile = new File("$buildDir/docker/Dockerfile")
    dockerFile.text = """
    FROM amazoncorretto:17-alpine-jdk
    RUN apk update && apk add bash && apk add coreutils && apk add curl
    COPY .nextflow /.nextflow
    COPY nextflow /usr/local/bin/nextflow
    COPY entry.sh /usr/local/bin/entry.sh
    ENV NXF_HOME=/.nextflow
    RUN chmod +x /usr/local/bin/nextflow /usr/local/bin/entry.sh
    RUN nextflow info
    ENTRYPOINT ["/usr/local/bin/entry.sh"]
    """
    // RUN nextflow info
    // COPY dist/docker /usr/local/bin/docker
    // curl -fsSLO https://get.docker.com/builds/Linux/x86_64/docker-17.03.1-ce.tgz && tar --strip-components=1 -xvzf docker-17.03.1-ce.tgz -C dist

    def temp = File.createTempFile('upload',null)
    temp.deleteOnExit()
    temp.text =  """\
    mkdir -p $target
    cp $source $target
    cp nextflow $buildDir/docker
    cp docker/entry.sh $buildDir/docker
    cd $buildDir/docker  
    mkdir -p dist
    NXF_HOME=\$PWD/.nextflow ./nextflow info
    docker build -t nextflow/nextflow:$version .
    """.stripIndent()

    commandLine 'bash', '-e', temp.absolutePath
}

/*
 * Tag and upload the release
 * 
 * https://github.com/aktau/github-release/
 */
task release(type: Exec, dependsOn: [pack, dockerImage]) {

    def launcherFile = file('nextflow').absoluteFile
    def launcherSha1 = file('nextflow.sha1').absoluteFile
    def launcherSha256 = file('nextflow.sha256').absoluteFile
    def nextflowAllFile = file("$releaseDir/nextflow-${version}-all")
    def versionFile = file('VERSION').absoluteFile

    def snapshot = version ==~ /^.+(-RC\d+|-SNAPSHOT)$/
    def edge = version ==~ /^.+(-edge|-EDGE)$/
    def isLatest = !snapshot && !edge
    def cmd =  """\
    # tag the release
    git push || exit \$?
    (git tag -a v$version -m 'Tagging version $version [release]' -f && git push origin v$version -f) || exit \$?
    sleep 1
    gh release create --repo nextflow-io/nextflow --prerelease --title "Version $version" v$version 
    sleep 1
    gh release upload --repo nextflow-io/nextflow v$version ${launcherFile} ${nextflowAllFile}        
    """.stripIndent()

    if( edge )
        cmd += """
        # publish the script as the latest
        export AWS_ACCESS_KEY_ID=${System.env.NXF_AWS_ACCESS}
        export AWS_SECRET_ACCESS_KEY=${System.env.NXF_AWS_SECRET}
        aws s3 cp $launcherFile s3://www2.nextflow.io/releases/edge/nextflow $s3CmdOpts
        aws s3 cp $launcherSha1 s3://www2.nextflow.io/releases/edge/nextflow.sha1 $s3CmdOpts
        aws s3 cp $launcherSha256 s3://www2.nextflow.io/releases/edge/nextflow.sha256 $s3CmdOpts
        aws s3 cp $launcherSha256 s3://www2.nextflow.io/releases/edge/nextflow.md5 $s3CmdOpts
        aws s3 cp $versionFile s3://www2.nextflow.io/releases/edge/version $s3CmdOpts
        """.stripIndent()
        
    else if( isLatest )
        cmd += """
        # publish the script as the latest
        export AWS_ACCESS_KEY_ID=${System.env.NXF_AWS_ACCESS}
        export AWS_SECRET_ACCESS_KEY=${System.env.NXF_AWS_SECRET}
        aws s3 cp $launcherFile s3://www2.nextflow.io/releases/latest/nextflow $s3CmdOpts
        aws s3 cp $launcherSha1 s3://www2.nextflow.io/releases/latest/nextflow.sha1 $s3CmdOpts
        aws s3 cp $launcherSha256 s3://www2.nextflow.io/releases/latest/nextflow.sha256 $s3CmdOpts
        aws s3 cp $launcherSha256 s3://www2.nextflow.io/releases/latest/nextflow.md5 $s3CmdOpts
        aws s3 cp $versionFile s3://www2.nextflow.io/releases/latest/version $s3CmdOpts
        """.stripIndent()

    def temp = File.createTempFile('upload',null)
    temp.deleteOnExit()
    temp.text = cmd
    commandLine 'bash', '-e', temp.absolutePath
}


