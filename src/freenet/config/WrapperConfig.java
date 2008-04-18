package freenet.config;

import java.io.BufferedInputStream;
import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;

import org.tanukisoftware.wrapper.WrapperManager;

import freenet.node.NodeInitException;
import freenet.support.Logger;
import freenet.support.io.Closer;

/**
 * Class to allow us to easily change wrapper properties.
 * @author toad
 */
public class WrapperConfig {
	
	public String getWrapperProperty(String name) {
		return WrapperManager.getProperties().getProperty(name, null);
	}
	
	public boolean canChangeProperties() {
		if(!WrapperManager.isControlledByNativeWrapper()) return false;
		File f = new File("wrapper.conf");
		if(!f.exists()) return false;
		if(!f.canRead()) return false;
		if(!f.canWrite()) return false;
		if(!f.getParentFile().canWrite()) return false; // Can we create a file in order to rename over wrapper.conf?
		return true;
	}

	/**
	 * Synchronized because we only want one instance running at once.
	 * @param name The property to set.
	 * @param value The value to set it to.
	 * @return True if the property was successfully updated.
	 */
	public synchronized boolean setWrapperProperty(String name, String value) {
		// Some of this copied from UpdateDeployContext, hence no GPL header on this file as none there.
		
		File oldConfig = new File("wrapper.conf");
		File newConfig = new File("wrapper.conf.new");
		
		FileInputStream fis = null;
		FileOutputStream fos = null;
		
		try {
		
			fis = new FileInputStream(oldConfig);
			BufferedInputStream bis = new BufferedInputStream(fis);
			InputStreamReader isr = new InputStreamReader(bis);
			BufferedReader br = new BufferedReader(isr);
			
			fos = new FileOutputStream(newConfig);
			OutputStreamWriter osw = new OutputStreamWriter(fos);
			BufferedWriter bw = new BufferedWriter(osw);
			
			String line;
			
			boolean written = false;
			boolean writtenReload = false;
			
			while((line = br.readLine()) != null) {
				
				if(line.startsWith(name+"=")) {
					bw.write(name+'='+value+'\n');
					written = true;
				} else if(line.equalsIgnoreCase("wrapper.restart.reload_configuration=TRUE")) {
					writtenReload = true;
				} else {
					bw.write(line+'\n');
				}
			
			}
			br.close();
			fis = null;
			if(!written)
				bw.write(name+'='+value+'\n');
			if(!writtenReload)
				bw.write("wrapper.restart.reload_configuration=TRUE\n");
			bw.close();
			fos = null;
		} catch(IOException e) {
			Closer.close(fis);
			Closer.close(fos);
			fis = null;
			fos = null;
			if(oldConfig.exists()) newConfig.delete();
			Logger.error(this, "Cannot update wrapper property "+"name: "+e, e);
			System.err.println("Unable to update wrapper property "+name+" : "+e);
			return false;
		} finally {
			Closer.close(fis);
			Closer.close(fos);
		}
		
		if(!newConfig.renameTo(oldConfig)) {
			File oldOldConfig = new File("wrapper.conf.old");
			if(oldOldConfig.exists() && !oldOldConfig.delete())
				try {
					oldOldConfig = File.createTempFile("wrapper.conf", ".old.tmp", new File("."));
				} catch (IOException e) {
					String error = "Unable to create temporary file and unable to copy wrapper.conf to wrapper.conf.old. Could not update wrapper.conf trying to set property "+name;
					Logger.error(this, error);
					System.err.println(error);
					return false;
				}
			if(!oldConfig.renameTo(oldOldConfig)) {
				String error = "Unable to change property "+name+": Could not move old config file "+oldConfig+", so could not rename new config file "+newConfig+" over it (already tried without deleting.";
				Logger.error(this, error);
				System.err.println(error);
				return false;
			}
			if(!newConfig.renameTo(oldConfig)) {
				String error = "Unable to rename "+newConfig+" to "+oldConfig+" even after moving the old config file out of the way! Trying to restore previous config file so the node will start up...";
				Logger.error(this, error);
				System.err.println(error);
				if(!oldOldConfig.renameTo(oldConfig)) {
					System.err.println("CATASTROPHIC UPDATE ERROR: Unable to rename backup copy of config file over the current config file, after failing to update config file! The node will not boot until you get a new wrapper.conf!\n"+
							"The old config file is saved in "+oldOldConfig+" and it should be renamed to wrapper.conf");
					System.exit(NodeInitException.EXIT_BROKE_WRAPPER_CONF);
				}
			}
		}
		return true;
	}
	
}
