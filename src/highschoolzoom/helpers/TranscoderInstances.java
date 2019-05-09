package highschoolzoom.helpers;

import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;

import com.wowza.wms.logging.WMSLogger;
import com.wowza.wms.logging.WMSLoggerFactory;

public class TranscoderInstances {
	private Map<String, TranscoderInfo> transcoders = new HashMap<>();
	private String dataFile = System.getProperty("java.io.tmpdir") + "/instances.txt";
	static WMSLogger logger = WMSLoggerFactory.getLogger(TranscoderInstances.class);
	
	public TranscoderInstances(){
		restore();
	}
	
	public TranscoderInfo put(String key, TranscoderInfo val){
		TranscoderInfo retval = transcoders.put(key, val);
		flush();
		return retval;
	}
	
	public TranscoderInfo get(String key){
		TranscoderInfo val = transcoders.get(key);
		return val;
	}
	
	public TranscoderInfo remove(String key){
		TranscoderInfo retval = transcoders.remove(key);
		flush();
		return retval;
	}
	
	public Set<String> getKeys(){
		return transcoders.keySet();
	}
	
	private void flush() {
		ObjectOutputStream out;
		try {
			out = new ObjectOutputStream(new FileOutputStream(dataFile));
			out.writeObject(transcoders);
			out.close();
		} catch (FileNotFoundException e) {
			e.printStackTrace();
		} catch (IOException e) {
			e.printStackTrace();
		}
	}
	
	@SuppressWarnings("unchecked")
	private void restore(){
		try {
			FileInputStream door = new FileInputStream(dataFile);
			ObjectInputStream reader = new ObjectInputStream(door);
			transcoders = (Map<String, TranscoderInfo>) reader.readObject();
			reader.close();
		} catch (FileNotFoundException e) {
			//e.printStackTrace();
			logger.info("Data file not found (" + dataFile + "). Initializing....");
		} catch (IOException e) {
			e.printStackTrace();
		} catch (ClassNotFoundException e) {
			e.printStackTrace();
		} 
	}

}
	