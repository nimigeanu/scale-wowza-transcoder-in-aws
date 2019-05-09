package highschoolzoom.helpers;

import java.io.Serializable;

public class TranscoderInfo implements Serializable{
	private static final long serialVersionUID = -6706144084860306031L;
	public String instanceId;
	public String privateIp;
	
	public TranscoderInfo(String id, String ip){
		instanceId = id;
		privateIp = ip;
	}
}