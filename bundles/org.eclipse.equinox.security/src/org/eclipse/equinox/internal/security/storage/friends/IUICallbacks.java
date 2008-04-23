/*******************************************************************************
 * Copyright (c) 2008 IBM Corporation and others.
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v1.0
 * which accompanies this distribution, and is available at
 * http://www.eclipse.org/legal/epl-v10.html
 * 
 * Contributors:
 *     IBM Corporation - initial API and implementation
 *******************************************************************************/
package org.eclipse.equinox.internal.security.storage.friends;

import org.eclipse.equinox.security.storage.StorageException;
import org.eclipse.equinox.security.storage.provider.IPreferencesContainer;

/**
 * Interface used for UI dependency injection. Unlike most places,
 * actions in the secure storage can be initiated independently from UI so
 * we can't expect UI bundle to be originator of an action - or even being 
 * activated.
 * 
 * As such, an internal extension point internalUI is used to get UI callbacks.
 * 
 * This is an internal interface used to facilitate exchange between core and
 * UI portions. 
 * 
 * This interface is subject to modifications as all internal code is.
 * 
 * Clients should not extend or implement this interface.
 */
public interface IUICallbacks {

	public void setupPasswordRecovery(final int size, String moduleID, IPreferencesContainer container);

	/**
	 * Ask user a yes/no question
	 * @param msg question to ask
	 * @return True for Yes, False for No, null if can't ask
	 */
	public Boolean ask(final String msg);

	/**
	 * @param callback
	 * @return false if task was canceled
	 * @throws StorageException
	 */
	public boolean execute(final IStorageTask callback) throws StorageException;

	/**
	 * @return true if running with UI; false if headless
	 */
	public boolean runningUI();

}
