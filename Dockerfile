#FROM registry.cn-hangzhou.aliyuncs.com/wybioinfo/ubuntu-jdk:19
FROM registry.cn-hangzhou.aliyuncs.com/sj-bioinfo/ubuntu-jdk:19
#RUN apt-get update -y
#RUN apt-get install -y  wget debianutils
#RUN apt-get clean
ENV NXF_HOME=/.nextflow
COPY entry.sh /usr/local/bin/entry.sh
RUN mkdir /.nextflow 
RUN chmod 755 /usr/local/bin/entry.sh

WORKDIR /bin
COPY build/releases/nextflow-23.11.0-edge-all nextflow
WORKDIR /home
#RUN apt-get update -y && apt-get install  iputils-ping -y
RUN ln -s /bin/nextflow /bin/nf

ENTRYPOINT ["/usr/local/bin/entry.sh"]
# ENTRYPOINT ["nextflow-22.11.0-edge-all"]
# docker build -t wybioinfo/nextflow:23.11.0  .
# docker login
# docker push wybioinfo/nextflow:23.11.0
# docker run --rm wybioinfo/nextflow:23.11.0 nf


