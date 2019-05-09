package highschoolzoom.modules;

import java.util.HashMap;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.Timer;
import java.util.TimerTask;
import java.util.stream.Stream;

import com.amazonaws.auth.AWSStaticCredentialsProvider;
import com.amazonaws.auth.BasicAWSCredentials;
import com.amazonaws.services.ec2.AmazonEC2;
import com.amazonaws.services.ec2.AmazonEC2ClientBuilder;
import com.amazonaws.services.ec2.model.DescribeInstancesRequest;
import com.amazonaws.services.ec2.model.DescribeInstancesResult;
import com.amazonaws.services.ec2.model.Instance;
import com.amazonaws.services.ec2.model.Reservation;
import com.amazonaws.services.ec2.model.RunInstancesRequest;
import com.amazonaws.services.ec2.model.RunInstancesResult;
import com.amazonaws.services.ec2.model.TerminateInstancesRequest;
import com.amazonaws.services.ec2.model.TerminateInstancesResult;
import com.wowza.wms.amf.AMFPacket;
import com.wowza.wms.application.IApplicationInstance;
import com.wowza.wms.logging.WMSLogger;
import com.wowza.wms.logging.WMSLoggerFactory;
import com.wowza.wms.medialist.MediaList;
import com.wowza.wms.medialist.MediaListRendition;
import com.wowza.wms.medialist.MediaListSegment;
import com.wowza.wms.module.*;
import com.wowza.wms.stream.*;

import highschoolzoom.helpers.StreamManager;
import highschoolzoom.helpers.TranscoderInfo;
import highschoolzoom.helpers.TranscoderInstances;

public class ExternalTranscoderManagerModule extends ModuleBase implements IModuleOnStream {
	private TranscoderInstances transcoderInstances = new TranscoderInstances();
	private HashMap<String, Timer> terminateTimers = new HashMap<>();
		/*
	 * Do not terminate an external transcoder immediately after unpublish. 
	 * Instead wait a minute or 2 in case the stream gets republished.
	 */
	private int terminateAfter = 1 * 60;  
	
	private String awsRegion = "us-east-1";
	private String awsAccessKey;
	private String awsSecretKey;
	
	private String transcoderAMI;
	private String transcoderKeyPair;
	private String transcoderSG;
	private String transcoderInstanceType;
	private String[] transcoderStreams;
	private String[] transcoderBitrates;
	private String[] transcoderResolutions;
	
	private IApplicationInstance appInstance;
	private StreamManager streamManager;
	private AmazonEC2 ec2;
	
	static WMSLogger logger = WMSLoggerFactory.getLogger(ExternalTranscoderManagerModule.class);
	
	public void onAppStart(IApplicationInstance inst) {
		appInstance = inst;
		
		awsRegion    = appInstance.getProperties().getPropertyStr("awsRegion", awsRegion);
		awsAccessKey = appInstance.getProperties().getPropertyStr("awsAccessKey", awsAccessKey);
		awsSecretKey = appInstance.getProperties().getPropertyStr("awsSecretKey", awsSecretKey);
		transcoderAMI = appInstance.getProperties().getPropertyStr("awsExternalTranscoderInstanceAMI", transcoderAMI);
		transcoderKeyPair = appInstance.getProperties().getPropertyStr("awsExternalTranscoderInstanceKeyPair", transcoderKeyPair);
		transcoderSG = appInstance.getProperties().getPropertyStr("awsExternalTranscoderInstanceSecurityGroup", transcoderSG);
		transcoderInstanceType = appInstance.getProperties().getPropertyStr("awsExternalTranscoderInstanceType", transcoderInstanceType);
		transcoderStreams = appInstance.getProperties().getPropertyStr("externalTranscoderStreams").split(",");
		transcoderBitrates = appInstance.getProperties().getPropertyStr("externalTranscoderBitrates").split(",");
		transcoderResolutions = appInstance.getProperties().getPropertyStr("externalTranscoderResolutions").split(",");
		terminateAfter = appInstance.getProperties().getPropertyInt("externalTranscoderTerminateAfterSeconds", terminateAfter);

		streamManager = new StreamManager(appInstance);
		appInstance.setStreamNameAliasProvider(streamManager);
		appInstance.setMediaListProvider(new MediaListProvider());
		
		logger.info("onAppStart: " + appInstance.getName());
		logger.info("terminateAfter: " + terminateAfter);
		
		BasicAWSCredentials credentials = new BasicAWSCredentials(awsAccessKey, awsSecretKey);
		
		ec2 = AmazonEC2ClientBuilder.standard()
	            .withCredentials(new AWSStaticCredentialsProvider(credentials))
	            .withRegion(awsRegion)
	            .build();
		
		cleanupTranscoders();
	}
	
	private void cleanupTranscoders() {
		logger.info("cleanupTranscoders()");
		Set<String> streamNames = transcoderInstances.getKeys();
		for(String streamName : streamNames){
			onStreamUnpublished(streamName);
		}
	}

	void onStreamPublished(String streamName){
		if (streamName.contains("_")) return;
		logger.info("onStreamPublished: " + streamName);
		//see if a transcoder already exists for this stream
		TranscoderInfo transcoder = transcoderInstances.get(streamName);
		logger.info("transcoder: " + transcoder);
		if (transcoder != null){
			//the instance may be pending termination; cancel that
			Timer terminationTimer = terminateTimers.get(streamName);
			if (terminationTimer != null){
				terminationTimer.cancel();
			}
		}
		else {
			transcoder = startTranscoderForStream(streamName);
		}
		
		setupTranscodeStreaming(streamName, transcoder.privateIp);
	}
	
	TranscoderInfo startTranscoderForStream(String streamName){
		logger.info("startInstanceForStream: " + streamName);
		TranscoderInfo transcoder = null;
		
		RunInstancesRequest runInstancesRequest =
				   new RunInstancesRequest();
		
		logger.info("transcoderAMI: " + transcoderAMI);
		
		runInstancesRequest.withImageId(transcoderAMI)
		                   .withInstanceType(transcoderInstanceType)
		                   .withMinCount(1)
		                   .withMaxCount(1)
		                   .withKeyName(transcoderKeyPair)
		                   .withSecurityGroups(transcoderSG);
		
		logger.info("ImageId: " + runInstancesRequest.getImageId());
		RunInstancesResult result = ec2.runInstances(
                runInstancesRequest);
		Object[] instances = result.getReservation().getInstances().toArray();
		if (instances.length == 1){
			Instance instance = (Instance) instances[0];
			String instanceId = instance.getInstanceId();
			logger.info("instanceId: " + instanceId);
			
			DescribeInstancesRequest describeInstacesRequest = new DescribeInstancesRequest()
                	.withInstanceIds(instanceId); 
			logger.info("describeInstacesRequest: " + describeInstacesRequest);
			DescribeInstancesResult describeInstancesResult = ec2.describeInstances(describeInstacesRequest);
			logger.info("describeInstacesRequest: " + describeInstacesRequest);
			List<Reservation> reservations = describeInstancesResult.getReservations();
			logger.info("reservations: " + reservations);
			Stream<Reservation> reservationStream = reservations.stream();
			logger.info("reservationStream: " + reservationStream);
			Stream<List<Instance>> reservationStreamMap = reservationStream.map(Reservation::getInstances);
			logger.info("reservationStreamMap: " + reservationStreamMap);
			Stream<Instance> reservationFlatMap = reservationStreamMap.flatMap(List::stream);
			logger.info("reservationFlatMap: " + reservationFlatMap);
			Optional<Instance> first = reservationFlatMap.findFirst();
			logger.info("first: " + first);
			String id = (String) first.map(Instance::getInstanceId).orElse(null);
			logger.info("id: " + id);
			String ip = (String) first.map(Instance::getPrivateIpAddress).orElse(null);
			logger.info("ip: " + ip);
			
			String privateIp = ec2.describeInstances(new DescribeInstancesRequest()
                	.withInstanceIds(instanceId))
			.getReservations()
			.stream()
			.map(Reservation::getInstances)
			.flatMap(List::stream)
			.findFirst()
			.map(Instance::getPrivateIpAddress)
			.orElse(null);
			logger.info("privateIp: " + privateIp);
			transcoder = new TranscoderInfo(instanceId, privateIp);
			transcoderInstances.put(streamName, transcoder);
		}
		else {
			logger.error("Assert #13535441"); //this should not happen
		}
		return transcoder;
	}
	
	private void setupTranscodeStreaming(String streamName, String privateIp) {
		logger.info("setupTranscodeStreaming: " + streamName + "  " + privateIp);
		
		logger.info("privateIp: " + privateIp);
		streamManager.pushStream(streamName, privateIp, "transcode");
		for (String suffix : transcoderStreams){
			streamManager.pullStream(streamName + "_" + suffix, privateIp, "transcode");
		}
	}

	void onStreamUnpublished(String streamName){
		logger.info("onStreamUnpublished: " + streamName);
		if (streamName.contains("_")) return;
		logger.info("onStreamUnpublished: " + streamName);
		Timer timer = new Timer();
		timer.schedule(new TimerTask() {
			  @Override
			  public void run() {
				  stopInstanceOfStream(streamName);
			  }
		}, terminateAfter * 1000);
		terminateTimers.put(streamName, timer);
	}
	
	void stopInstanceOfStream(String streamName){
		logger.info("stopInstanceOfStream: " + streamName);
		TranscoderInfo transcoder = transcoderInstances.get(streamName);
		if (transcoder != null){
			String instanceId = transcoder.instanceId;
			TerminateInstancesRequest request = new TerminateInstancesRequest()
				    .withInstanceIds(instanceId);
			
			TerminateInstancesResult result = ec2.terminateInstances(request);
			Object[] instances = result.getTerminatingInstances().toArray();
			if (instances.length == 1){
				getLogger().info("stopping: " + result.getTerminatingInstances().toArray().length);
				transcoderInstances.remove(streamName);
				teardownTranscodeStreaming(streamName, instanceId);
			}
			else {
				logger.error("Failed to terminate instance " + instanceId); 
			}
		}
		else {
			logger.error("No running instance for stream " + streamName);
		}
	}
	
	private void teardownTranscodeStreaming(String streamName, String instanceId) {
		streamManager.stopPushStream(streamName);
		for (String suffix : transcoderStreams){
			streamManager.stopPullStream(streamName + "_" + suffix);
		}
	}

	class ActionNotify implements IMediaStreamActionNotify2 {

		@Override
		public void onPause(IMediaStream stream, boolean isPause, double location) {
		}

		@Override
		public void onPlay(IMediaStream stream, String streamName, double playStart, double playLen, int playReset) {
		}

		@Override
		public void onPublish(IMediaStream stream, String streamName, boolean isRecord, boolean isAppend) {
			//Map<String, String> parameters = QueryStringParser.parse(stream.getQueryStr(), true, getLogger());
			logger.info("onPublish: " + streamName);
			onStreamPublished(streamName);
		}

		@Override
		public void onSeek(IMediaStream stream, double location) {
		}

		@Override
		public void onStop(IMediaStream stream) {
		}

		@Override
		public void onUnPublish(IMediaStream stream, String streamName, boolean isRecord, boolean isAppend) {
			logger.info("onUnPublish: " + streamName);
			onStreamUnpublished(streamName);
		}

		@Override
		public void onMetaData(IMediaStream stream, AMFPacket metaDataPacket) {
		}

		@Override
		public void onPauseRaw(IMediaStream stream, boolean isPause, double location) {
		}
	}
	@Override
	public void onStreamCreate(IMediaStream stream) {
		stream.addClientListener(new ActionNotify());
	}

	@Override
	public void onStreamDestroy(IMediaStream stream) {
	}
	
	class MediaListProvider implements IMediaListProvider
	{
		public MediaList resolveMediaList(IMediaListReader mediaListReader, IMediaStream stream, String streamName)
		{
			MediaList mediaList = new MediaList();

			MediaListSegment segment = new MediaListSegment();
			mediaList.addSegment(segment);
			
			for (int i = 0; i < transcoderStreams.length; i++){
				String suffix = transcoderStreams[i];
				Integer bitrate = Integer.parseInt(transcoderBitrates[i]);
				String resStr = transcoderResolutions[i];
				Integer width = Integer.parseInt(resStr.split("x")[0]);
				Integer height = Integer.parseInt(resStr.split("x")[1]);
				
				MediaListRendition rendition = new MediaListRendition();
				segment.addRendition(rendition);
				
				rendition.setName(streamName + "_" + suffix);
				rendition.setBitrateVideo(bitrate);
				rendition.setWidth(width);
				rendition.setHeight(height);
				rendition.setAudioCodecId("mp4a.40.2");
				rendition.setVideoCodecId("avc1.66.12");
			}

			return mediaList;
		}
	}
}