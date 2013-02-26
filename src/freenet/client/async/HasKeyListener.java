package freenet.client.async;

import java.io.IOException;

import com.db4o.ObjectContainer;

/**
 * Interface to show that we can create a KeyListener callback.
 * @author Matthew Toseland <toad@amphibian.dyndns.org> (0xE43DA450)
 */
public interface HasKeyListener {
	
	/**
	 * Create a KeyListener, a transient object used to determine which keys we
	 * want, and to handle any blocks found.
	 * @return Null if the HasKeyListener is finished/cancelled/etc.
	 * @throws IOException 
	 */
	KeyListener makeKeyListener(ObjectContainer container, ClientContext context, boolean onStartup) throws KeyListenerConstructionException;

	/**
	 * Is it cancelled?
	 */
	boolean isCancelled(ObjectContainer container);

	/**
	 * Notify that makeKeyListener() failed.
	 */
	void onFailed(KeyListenerConstructionException e, ObjectContainer container, ClientContext context);
	
	/**
	 * @param container Database handle. This should be called on the database thread.
	 * @return Return the salted key if we only want one key. For SSKs, return pubKeyHash (not salted),
	 * for CHKs, return routingKey. Generally should just pass it to sched.saltKey(Key). Implementations
	 * should throw if they are supposed to return a key and it's null because they are deactivated.
	 */
	public byte[] getWantedKey(ObjectContainer container, ClientRequestSchedulerBase sched);

}
