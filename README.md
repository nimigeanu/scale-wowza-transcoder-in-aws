# Scaling the Wowza transcoding in AWS

## Abstract

Wowza transcoding is CPU/GPU instensive and a single server is only capable of transcoding a finite number of streams, no matter how powerful. 
Running multiple wowza servers can get complex to manage (i.e. what stream is where?) and costly in terms of both hardware (or virtual cloud resources) and licensing. Particularily regarding licensing, you will be paying for the server count you have used at peak during a month.

The setup makes use of Dev-Pay (hourly pay-as-you-go) Wowza instances launched as needed to accomodate any number of streams. 

## Prerequisites

Solution assumes you are familiar with Wowza and AWS and you have already deployed a single-server transcoder-driven Adaptive Bitrate setup that you need scaled. 

Rather than building an architecture from scratch, you are to modify your application (i.e. to accomodate distributed transcoding).

HLS, MPEG-DASH and HTTP Origin are supported. 

## Shortcomings

* There will be a delay between a stream being broadcast and its transcoded renditions being available; this is the time needed to start and boot an EC2 instance
* The playback URL format will change (i.e. from "ngrp:" to "amlst:")

## Setup

### Create the transcoder AMI

1. Launch a wowza dev-pay instance - i.e. `Wowza Streaming Engine (Linux PAID)`
2. Change its *admin password*
3. Create application `transcode`
4. Disable *source security* for RTMP
5. Disable all streaming other than RTMP
6. Set up transcoding for the `transcode` application, the same way it is implemented for your original application
7. create an *AMI* (machine instance) from this instance and make note of its *AMI ID*
8. terminate the instance

### Modify your main server and application
1. upload provided `scaletranscode.jar` and `aws-java-sdk-ec2-x.xx.xxx.jar` to Wowza's `lib` folder
2. disable transcoding for your application
3. add module `highschoolzoom.modules.ExternalTranscoderManagerModule` to your application
4. add the following custom properties to your application
 * `awsRegion` (type *String*) - *AWS region* to deploy transcoders in (e.g. `us-west-1`)
 * `awsAccessKey` (type *String*) - the *AWS access key* of an account that has API access to EC2 (e.g. `AKIAXXXXXXXXXXXXXXAA`)
 * `awsSecretKey` (type *String*) - the *AWS secret key* corresponding to above *access key* (e.g. `c05zl9vxxxxxxxxxxxxxxxxxxxxxxxxxxxnI2IhV`)
 * `awsExternalTranscoderInstanceAMI` (type *String*) - AMI of the transcoder created above (i.e. `ami-0611xxxxxxxxce7e3`)
 * `awsExternalTranscoderInstanceKeyPair` (type *String*) - name of the *key pair* to to be used for your transcoder instances (e.g. `my_key_pair`)
 * `awsExternalTranscoderInstanceSecurityGroup` (type *String*) - name of the *security group*  to to be used for your transcoder instances (e.g. `my-security-group`)
 * `awsExternalTranscoderInstanceType` (type *String*) - *type* of your transcoder instances (e.g. `m4.xlarge`)
 * `externalTranscoderStreams` (type *String*) - comma separated list of *transcoded stream suffixes* (e.g. `720p,360p`)
 * `externalTranscoderResolutions` (type *String*) - comma separated list of *transcoded stream resolutions* (e.g. `1280x720,640x360`)
 * `externalTranscoderBitrates` (type *String*) - comma separated list of transcoded stream bitrates (e.g. `1428000,928000`)
 * `externalTranscoderTerminateAfterSeconds` (type *Integer*) - how long after unpublish to wait (seconds) before terminating a transcoder instance (i.e. `60`)

## Notes
* There will be a delay between publishing a stream, and it being available for playback; this is the time needed to start a transcoding instance, starting its software (Wowza included), proxying streams, starting the actual transcode, etc
* A transcoder will not be terminated immediately, instead the manager will wait for a set time for the stream to be re-publihsed; this is meant to smooth out short disconnects; delay is configurable via the `externalTranscoderTerminateAfterSeconds` parameter above
* There is a *cleanup* system in place that saves the list of running transcoders to disk; in case of Wowza failure (or deliberate restart) the running transcoders will be properly terminated and/or reused
* master will push and pull streams to/from transcoders via their private ip; enhanced security for the transcoders can be achieved by restricting access via their security group