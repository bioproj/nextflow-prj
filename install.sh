export JAVA_HOME=/home/wangyang/software/jdk-19
export JAVA_HOME=/home/wangyang/software/jdk-21.0.4
export PATH=$JAVA_HOME/bin:$PATH
gradle8.4 build  -x test
#BUILD_PACK=1 gradle8.4  packAll
BUILD_PACK=1 gradle8.4 pack