#!/bin/bash

docker build -t registry.cn-hangzhou.aliyuncs.com/wybioinfo/nextflowdev:23.11.0  .
docker tag  wybioinfo/nextflow:23.11.0  registry.cn-hangzhou.aliyuncs.com/wybioinfo/nextflowdev:23.11.0
docker push registry.cn-hangzhou.aliyuncs.com/wybioinfo/nextflowdev:23.11.0