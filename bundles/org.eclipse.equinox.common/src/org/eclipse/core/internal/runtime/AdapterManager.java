/*******************************************************************************
 * Copyright (c) 2000, 2015 IBM Corporation and others.
 *
 * This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License 2.0
 * which accompanies this distribution, and is available at
 * https://www.eclipse.org/legal/epl-2.0/
 *
 * SPDX-License-Identifier: EPL-2.0
 * 
 * Contributors:
 *     IBM Corporation - initial API and implementation
 *     David Green - fix factories with non-standard class loading (bug 200068) 
 *     Filip Hrbek - fix thread safety problem described in bug 305863
 *     Sergey Prigogin (Google) - use parameterized types (bug 442021)
 *******************************************************************************/
package org.eclipse.core.internal.runtime;

import java.util.*;
import java.util.AbstractMap.SimpleEntry;
import java.util.Map.Entry;
import java.util.stream.Collectors;
import org.eclipse.core.runtime.*;

/**
 * This class is the standard implementation of <code>IAdapterManager</code>. It provides
 * fast lookup of property values with the following semantics:
 * <ul>
 * <li>If multiple installed factories provide the same adapter, iterate until one of the factories
 * return a non-<code>null</code> value. Remaining factories won't be invoked.</li>
 * <li>The search order from a class with the definition <br>
 * <code>class X extends Y implements A, B</code><br> is as follows:
 * <ul>
 * <li>the target's class: X
 * <li>X's superclasses in order to <code>Object</code>
 * <li>a breadth-first traversal of each class's interfaces in the
 * order returned by <code>getInterfaces</code> (in the example, X's 
 * superinterfaces then Y's superinterfaces) </li>
 * </ul>
 * </ul>
 * 
 * @see IAdapterFactory
 * @see IAdapterManager
 */
public final class AdapterManager implements IAdapterManager {
	/** 
	 * Cache of adapters for a given adaptable class. Maps String  -> Map
	 * (adaptable class name -> (adapter class name -> factory instance))
	 * Thread safety note: The outer map is synchronized using a synchronized
	 * map wrapper class.  The inner map is not synchronized, but it is immutable
	 * so synchronization is not necessary.
	 */
	private Map<String, Map<String, List<IAdapterFactory>>> adapterLookup;

	/**
	 * Cache of classes for a given type name. Avoids too many loadClass calls.
	 * (factory -> (type name -> Class)).
	 * Thread safety note: Since this structure is a nested hash map, and both
	 * the inner and outer maps are mutable, access to this entire structure is
	 * controlled by the classLookupLock field.  Note the field can still be
	 * nulled concurrently without holding the lock.
	 */
	private Map<IAdapterFactory, Map<String, Class<?>>> classLookup;

	/**
	 * The lock object controlling access to the classLookup data structure.
	 */
	private final Object classLookupLock = new Object();

	/**
	 * Cache of class lookup order (Class -> Class[]). This avoids having to compute often, and
	 * provides clients with quick lookup for instanceOf checks based on type name.
	 * Thread safety note: The map is synchronized using a synchronized
	 * map wrapper class.  The arrays within the map are immutable.
	 */
	private Map<Class<?>, Class<?>[]> classSearchOrderLookup;

	/**
	 * Map of factories, keyed by <code>String</code>, fully qualified class name of
	 * the adaptable class that the factory provides adapters for. Value is a <code>List</code>
	 * of <code>IAdapterFactory</code>.
	 */
	private final Map<String, List<IAdapterFactory>> factories;

	private final ArrayList<IAdapterManagerProvider> lazyFactoryProviders;

	private static final AdapterManager singleton = new AdapterManager();

	private static final Comparator<? super IAdapterFactory> ACTIVE_FIRST = new Comparator<IAdapterFactory>() {

		@Override
		public int compare(IAdapterFactory o1, IAdapterFactory o2) {
			boolean factory1Loaded = isFactoryLoaded(o1);
			boolean factory2Loaded = isFactoryLoaded(o2);
			if (factory1Loaded == factory2Loaded) {
				return 0;
			}
			if (factory1Loaded && !factory2Loaded) {
				return -1;
			}
			if (!factory1Loaded && factory2Loaded) {
				return +1;
			}
			return 0;
		}

	};

	public static AdapterManager getDefault() {
		return singleton;
	}

	/**
	 * Private constructor to block instance creation.
	 */
	private AdapterManager() {
		factories = new HashMap<>(5);
		lazyFactoryProviders = new ArrayList<>(1);
	}

	private static boolean isFactoryLoaded(IAdapterFactory adapterFactory) {
		return (!(adapterFactory instanceof IAdapterFactoryExt)) || ((IAdapterFactoryExt) adapterFactory).loadFactory(false) != null;
	}

	/**
	 * Given a type name, add all of the factories that respond to those types into
	 * the given table. Each entry will be keyed by the adapter class name (supplied in
	 * IAdapterFactory.getAdapterList).
	 */
	private void addFactoriesFor(String adaptableTypeName, Map<String, List<IAdapterFactory>> table) {
		List<IAdapterFactory> factoryList = getFactories().get(adaptableTypeName);
		if (factoryList == null)
			return;
		for (IAdapterFactory factory : factoryList) {
			if (factory instanceof IAdapterFactoryExt) {
				String[] adapters = ((IAdapterFactoryExt) factory).getAdapterNames();
				for (String adapter : adapters) {
					table.computeIfAbsent(adapter, any -> new ArrayList<>(1)).add(factory);
				}
			} else {
				Class<?>[] adapters = factory.getAdapterList();
				for (Class<?> adapter : adapters) {
					table.computeIfAbsent(adapter.getName(), any -> new ArrayList<>(1)).add(factory);
				}
			}
		}
	}

	private void cacheClassLookup(IAdapterFactory factory, Class<?> clazz) {
		synchronized (classLookupLock) {
			//cache reference to lookup to protect against concurrent flush
			Map<IAdapterFactory, Map<String, Class<?>>> lookup = classLookup;
			if (lookup == null)
				classLookup = lookup = new HashMap<>(4);
			Map<String, Class<?>> classes = lookup.get(factory);
			if (classes == null) {
				classes = new HashMap<>(4);
				lookup.put(factory, classes);
			}
			classes.put(clazz.getName(), clazz);
		}
	}

	private Class<?> cachedClassForName(IAdapterFactory factory, String typeName) {
		synchronized (classLookupLock) {
			Class<?> clazz = null;
			//cache reference to lookup to protect against concurrent flush
			Map<IAdapterFactory, Map<String, Class<?>>> lookup = classLookup;
			if (lookup != null) {
				Map<String, Class<?>> classes = lookup.get(factory);
				if (classes != null) {
					clazz = classes.get(typeName);
				}
			}
			return clazz;
		}
	}

	/**
	 * Returns the class with the given fully qualified name, or null
	 * if that class does not exist or belongs to a plug-in that has not
	 * yet been loaded.
	 */
	private Class<?> classForName(IAdapterFactory factory, String typeName) {
		Class<?> clazz = cachedClassForName(factory, typeName);
		if (clazz == null) {
			if (factory instanceof IAdapterFactoryExt)
				factory = ((IAdapterFactoryExt) factory).loadFactory(false);
			if (factory != null) {
				try {
					clazz = factory.getClass().getClassLoader().loadClass(typeName);
				} catch (ClassNotFoundException e) {
					// it is possible that the default bundle classloader is unaware of this class
					// but the adaptor factory can load it in some other way. See bug 200068.
					if (typeName == null)
						return null;
					Class<?>[] adapterList = factory.getAdapterList();
					clazz = null;
					for (Class<?> adapter : adapterList) {
						if (typeName.equals(adapter.getName())) {
							clazz = adapter;
							break;
						}
					}
					if (clazz == null)
						return null; // class not yet loaded
				}
				cacheClassLookup(factory, clazz);
			}
		}
		return clazz;
	}

	@Override
	public String[] computeAdapterTypes(Class<? extends Object> adaptable) {
		Set<String> types = getFactories(adaptable).keySet();
		return types.toArray(new String[types.size()]);
	}

	/**
	 * Computes the adapters that the provided class can adapt to, along
	 * with the factory object that can perform that transformation. Returns 
	 * a table of adapter class name to factory object.
	 */
	private Map<String, List<IAdapterFactory>> getFactories(Class<? extends Object> adaptable) {
		//cache reference to lookup to protect against concurrent flush
		Map<String, Map<String, List<IAdapterFactory>>> lookup = adapterLookup;
		if (lookup == null)
			adapterLookup = lookup = Collections.synchronizedMap(new HashMap<String, Map<String, List<IAdapterFactory>>>(30));
		return lookup.computeIfAbsent(adaptable.getName(), adaptableType -> {
			// calculate adapters for the class
			Map<String, List<IAdapterFactory>> table = new HashMap<>(4);
			for (Class<?> cl : computeClassOrder(adaptable)) {
				addFactoriesFor(cl.getName(), table);
			}
			return table;
		});
	}

	/**
	 * Returns the super-type search order starting with <code>adaptable</code>. 
	 * The search order is defined in this class' comment.
	 */
	@Override
	@SuppressWarnings("unchecked")
	public <T> Class<? super T>[] computeClassOrder(Class<T> adaptable) {
		//cache reference to lookup to protect against concurrent flush
		Map<Class<?>, Class<?>[]> lookup = classSearchOrderLookup;
		if (lookup == null) {
			classSearchOrderLookup = lookup = Collections.synchronizedMap(new HashMap<Class<?>, Class<?>[]>());
		}
		return (Class<? super T>[]) lookup.computeIfAbsent(adaptable, this::doComputeClassOrder);
	}

	/**
	 * Computes the super-type search order starting with <code>adaptable</code>. 
	 * The search order is defined in this class' comment.
	 */
	private Class<?>[] doComputeClassOrder(Class<?> adaptable) {
		List<Class<?>> classes = new ArrayList<>();
		Class<?> clazz = adaptable;
		Set<Class<?>> seen = new HashSet<>(4);
		//first traverse class hierarchy
		while (clazz != null) {
			classes.add(clazz);
			clazz = clazz.getSuperclass();
		}
		//now traverse interface hierarchy for each class
		Class<?>[] classHierarchy = classes.toArray(new Class[classes.size()]);
		for (Class<?> cl : classHierarchy) {
			computeInterfaceOrder(cl.getInterfaces(), classes, seen);
		}
		return classes.toArray(new Class[classes.size()]);
	}

	private void computeInterfaceOrder(Class<?>[] interfaces, Collection<Class<?>> classes, Set<Class<?>> seen) {
		List<Class<?>> newInterfaces = new ArrayList<>(interfaces.length);
		for (Class<?> interfac : interfaces) {
			if (seen.add(interfac)) {
				//note we cannot recurse here without changing the resulting interface order
				classes.add(interfac);
				newInterfaces.add(interfac);
			}
		}
		for (Class<?> clazz : newInterfaces)
			computeInterfaceOrder(clazz.getInterfaces(), classes, seen);
	}

	/**
	 * Flushes the cache of adapter search paths. This is generally required whenever an
	 * adapter is added or removed.
	 * <p>
	 * It is likely easier to just toss the whole cache rather than trying to be smart
	 * and remove only those entries affected.
	 * </p>
	 */
	public synchronized void flushLookup() {
		adapterLookup = null;
		classLookup = null;
		classSearchOrderLookup = null;
	}

	@Override
	@SuppressWarnings("unchecked")
	public <T> T getAdapter(Object adaptable, Class<T> adapterType) {
		Assert.isNotNull(adaptable);
		Assert.isNotNull(adapterType);
		List<Entry<IAdapterFactory, Class<?>>> incorrectAdapters = new ArrayList<>();
		T adapterObject = getFactories(adaptable.getClass()).getOrDefault(adapterType.getName(), Collections.emptyList()) //
				.stream() //
				.map(factory -> new SimpleEntry<>(factory, factory.getAdapter(adaptable, adapterType))) //
				.filter(entry -> {
					Object adapter = entry.getValue();
					if (adapter == null) {
						return false;
					}
					boolean res = adapterType.isInstance(adapter);
					if (!res) {
						IAdapterFactory factory = entry.getKey();
						incorrectAdapters.add(new SimpleEntry<>(factory, adapter.getClass()));
					}
					return res;
				}).map(Entry::getValue) //
				.findFirst() //
				.orElse(null);
		if (adapterObject == null) {
			if (!incorrectAdapters.isEmpty()) {
				throw new AssertionFailedException(incorrectAdapters.stream().map(entry -> "Adapter factory " //$NON-NLS-1$
						+ entry.getKey() + " returned " + entry.getValue().getName() //$NON-NLS-1$
						+ " that is not an instance of " + adapterType.getName()).collect(Collectors.joining("\n"))); //$NON-NLS-1$ //$NON-NLS-2$
			}
			if (adapterType.isInstance(adaptable)) {
				return (T) adaptable;
			}
		}
		return adapterObject;
	}

	@Override
	public Object getAdapter(Object adaptable, String adapterType) {
		Assert.isNotNull(adaptable);
		Assert.isNotNull(adapterType);
		return getAdapter(adaptable, adapterType, false);
	}

	/**
	 * Returns an adapter of the given type for the provided adapter.
	 * @param adaptable the object to adapt
	 * @param adapterType the type to adapt the object to
	 * @param force <code>true</code> if the plug-in providing the
	 * factory should be activated if necessary. <code>false</code>
	 * if no plugin activations are desired.
	 */
	private Object getAdapter(Object adaptable, String adapterType, boolean force) {
		Assert.isNotNull(adaptable);
		Assert.isNotNull(adapterType);
		return getFactories(adaptable.getClass()).getOrDefault(adapterType, Collections.emptyList()) //
				.stream() //
				.sorted(ACTIVE_FIRST) // prefer factories from already active bundles to minimize activation and return earlier when possible
				.map(factory -> force && factory instanceof IAdapterFactoryExt ? ((IAdapterFactoryExt) factory).loadFactory(true) : factory) //
				.filter(Objects::nonNull).map(factory -> {
					Class<?> adapterClass = classForName(factory, adapterType);
					if (adapterClass == null) {
						return null;
					}
					return factory.getAdapter(adaptable, adapterClass); //
				}).filter(Objects::nonNull) //
				.findFirst() //
				.map(Object.class::cast) // casting to object seems necessary here; compiler issue?
				.orElseGet(() -> adapterType.equals(adaptable.getClass().getName()) ? adaptable : null);
	}

	@Override
	public boolean hasAdapter(Object adaptable, String adapterTypeName) {
		return getFactories(adaptable.getClass()).get(adapterTypeName) != null;
	}

	@Override
	public int queryAdapter(Object adaptable, String adapterTypeName) {
		List<IAdapterFactory> eligibleFactories = getFactories(adaptable.getClass()).get(adapterTypeName);
		if (eligibleFactories == null || eligibleFactories.isEmpty()) {
			return NONE;
		}
		if (eligibleFactories.stream().anyMatch(AdapterManager::isFactoryLoaded)) {
			return LOADED;
		}
		return NOT_LOADED;
	}

	@Override
	public Object loadAdapter(Object adaptable, String adapterTypeName) {
		return getAdapter(adaptable, adapterTypeName, true);
	}

	/*
	 * @see IAdapterManager#registerAdapters
	 */
	@Override
	public synchronized void registerAdapters(IAdapterFactory factory, Class<?> adaptable) {
		registerFactory(factory, adaptable.getName());
		flushLookup();
	}

	/*
	 * @see IAdapterManager#registerAdapters
	 */
	public void registerFactory(IAdapterFactory factory, String adaptableType) {
		factories.computeIfAbsent(adaptableType, any -> new ArrayList<>(5)).add(factory);
	}

	/*
	 * @see IAdapterManager#unregisterAdapters
	 */
	@Override
	public synchronized void unregisterAdapters(IAdapterFactory factory) {
		for (List<IAdapterFactory> list : factories.values())
			list.remove(factory);
		flushLookup();
	}

	/*
	 * @see IAdapterManager#unregisterAdapters
	 */
	@Override
	public synchronized void unregisterAdapters(IAdapterFactory factory, Class<?> adaptable) {
		List<IAdapterFactory> factoryList = factories.get(adaptable.getName());
		if (factoryList == null)
			return;
		factoryList.remove(factory);
		flushLookup();
	}

	/*
	 * Shuts down the adapter manager by removing all factories
	 * and removing the registry change listener. Should only be
	 * invoked during platform shutdown.
	 */
	public synchronized void unregisterAllAdapters() {
		factories.clear();
		flushLookup();
	}

	public void registerLazyFactoryProvider(IAdapterManagerProvider factoryProvider) {
		synchronized (lazyFactoryProviders) {
			lazyFactoryProviders.add(factoryProvider);
		}
	}

	public boolean unregisterLazyFactoryProvider(IAdapterManagerProvider factoryProvider) {
		synchronized (lazyFactoryProviders) {
			return lazyFactoryProviders.remove(factoryProvider);
		}
	}

	public Map<String, List<IAdapterFactory>> getFactories() {
		synchronized (lazyFactoryProviders) {
			while (!lazyFactoryProviders.isEmpty()) {
				IAdapterManagerProvider provider = lazyFactoryProviders.remove(0);
				if (provider.addFactories(this))
					flushLookup();
			}
		}
		return factories;
	}
}
