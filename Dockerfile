FROM registry.cn-hangzhou.aliyuncs.com/wybioinfo/nextflowdev:23.11.0

WORKDIR /bin
COPY build/releases/nextflow-23.11.0-edge-all nextflow
WORKDIR /home
#RUN apt-get update -y && apt-get install  iputils-ping -y
RUN ln -s /bin/nextflow /bin/nf
# ENTRYPOINT ["nextflow-22.11.0-edge-all"]
# docker build -t wybioinfo/nextflow:23.11.0  .
# docker login
# docker push wybioinfo/nextflow:23.11.0
# docker run --rm wybioinfo/nextflow:23.11.0 nf