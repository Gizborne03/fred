/* This code is part of Freenet. It is distributed under the GNU General
 * Public License, version 2 (or at your option any later version). See
 * http://www.gnu.org/ for further details of the GPL. */
package freenet.node.fcp;

import freenet.node.Node;
import freenet.node.PeerNode;
import freenet.support.SimpleFieldSet;

public class NodeData extends FCPMessage {
	static final String name = "NodeData";
	
	final Node node;
	final boolean withPrivate;
	final boolean withVolatile;
	
	public NodeData(Node node, boolean withPrivate, boolean withVolatile) {
		this.node = node;
		this.withPrivate = withPrivate;
		this.withVolatile = withVolatile;
	}
	
	public SimpleFieldSet getFieldSet() {
		SimpleFieldSet fs = new SimpleFieldSet();
		if(withPrivate) {
			fs = node.exportPrivateFieldSet();
		} else {
			fs = node.exportPublicFieldSet();
		}
		if(withVolatile) {
			SimpleFieldSet vol = node.exportVolatileFieldSet();
			if(!vol.isEmpty()) {
			 	fs.put("volatile", vol);
			}
		}
		return fs;
	}

	public String getName() {
		return name;
	}

	public void run(FCPConnectionHandler handler, Node node)
			throws MessageInvalidException {
		throw new MessageInvalidException(ProtocolErrorMessage.INVALID_MESSAGE, "NodeData goes from server to client not the other way around", null);
	}

}
