package highschoolzoom.helpers;

import java.util.HashMap;

import com.wowza.wms.application.IApplicationInstance;
import com.wowza.wms.logging.WMSLogger;
import com.wowza.wms.logging.WMSLoggerFactory;
import com.wowza.wms.mediacaster.MediaCasterStreamManager;
import com.wowza.wms.pushpublish.protocol.rtmp.PushPublishRTMP;
import com.wowza.wms.stream.IMediaStreamNameAliasProvider;

public class StreamManager implements IMediaStreamNameAliasProvider {
	HashMap<String, String> streamAliases = new HashMap<String, String>();
	HashMap<String, String> playAliases = new HashMap<String, String>();
	HashMap<String, PushPublishRTMP> publishers = new HashMap<>();
	
	private MediaCasterStreamManager streamManager;
	static WMSLogger logger = WMSLoggerFactory.getLogger(StreamManager.class);
	private IApplicationInstance appInstance;
	
	public StreamManager(IApplicationInstance inst){
		appInstance = inst;
		streamManager = inst.getMediaCasterStreams().getStreamManager();
	}
	
	public void pullStream(String streamName, String host, String app){
		logger.info("pullStream: " + streamName + "  " + host + "  " + app);
		String url = "wowz://" + host + ":1935/" + app + "/" + streamName;
		playStream(url, streamName);
	}
	
	public void stopPullStream(String streamName){
		logger.info("stopPullStream: " + streamName);
		stopStream(streamName);
	}
	
	public void pushStream(String streamName, String host, String app){
		logger.info("pushStream: " + streamName + "  " + host + "  " + app);
		try {
			PushPublishRTMP publisher = new PushPublishRTMP();
			publisher.setAppInstance(appInstance);
			publisher.setSrcStreamName(streamName);

			// Destination stream
			publisher.setHostname(host);
			publisher.setPort(1935);
			publisher.setDstApplicationName(app);
			
			publisher.setDstStreamName(streamName);
			publisher.setConnectionFlashVersion(PushPublishRTMP.CURRENTFMLEVERSION);

			publisher.setSendFCPublish(true);
			publisher.setSendReleaseStream(true);
			publisher.setDebugLog(true);

			publisher.connect();
			publishers.put(streamName, publisher);
		} catch (Exception e) {
			logger.info("PushPublish error: ", e);
		}
	}
	
	public void stopPushStream(String streamName){
		logger.info("stopPushStream: " + streamName);
		PushPublishRTMP publisher = publishers.get(streamName);
		if (publisher != null){
			publisher.disconnect(false);
		}
	}
	
	public void setStreamAlias(String alias, String name){
		logger.info("setStreamAlias: " + alias + " -> " + name);
		if (alias.equals(name)){
			return;
		}
		streamAliases.put(alias, name);
	}
	
	public void setPlayAlias(String alias, String name){
		logger.info("setPlayAlias: " + alias + " -> " + name);
		if (alias.equals(name)){
			return;
		}
		playAliases.put(alias, name);
	}
	
	public void clearStreamAlias(String alias){
		streamAliases.remove(alias);
	}
	
	public void clearPlayAlias(String alias){
		playAliases.remove(alias);
	}
	
	public String resolvePlayAlias(IApplicationInstance appInstance, String name) {
		logger.info("resolvePlayAlias[" + appInstance.getApplication().getName() + "]: " + name);
		boolean deadend = false;
		int killswitch = 100;
		do {
			if (playAliases.containsKey(name)){
				name = playAliases.get(name);
			}
			else {
				deadend = true;
			}
		} while (!deadend && killswitch-- > 0);
		logger.info("returnning: " + name);
		return name;
	}

	public String resolveStreamAlias(IApplicationInstance appInstance, String name) {
		logger.info("resolveStreamAlias[" + appInstance.getApplication().getName() + "]: " + name);
		boolean deadend = false;
		int killswitch = 100;
		do {
			if (streamAliases.containsKey(name)){
				name = streamAliases.get(name);
			}
			else {
				deadend = true;
			}
		} while (!deadend && killswitch-- > 0);
		logger.info("returnning: " + name);
		return name;
	}
	
	public void playStream(String url, String alias){
		setStreamAlias(alias, url);
		streamManager.startStream(alias, "liverepeater");
	}
	
	public void stopStream(String alias){
		streamManager.stopStream(alias);
		clearStreamAlias(alias);
	}
}
