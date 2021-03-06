/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.zkoss.zel;

import java.beans.BeanInfo;
import java.beans.FeatureDescriptor;
import java.beans.IndexedPropertyDescriptor;
import java.beans.IntrospectionException;
import java.beans.Introspector;
import java.beans.PropertyDescriptor;
import java.lang.reflect.Array;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.security.AccessController;
import java.security.PrivilegedAction;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Locale;
import java.util.Map;
import java.util.WeakHashMap;
import java.util.concurrent.ConcurrentHashMap;

import org.zkoss.zel.impl.util.ClassUtil;
import org.zkoss.zel.impl.util.ProxyUtils;

public class BeanELResolver extends ELResolver {

    private static final int CACHE_SIZE;
    private static final String CACHE_SIZE_PROP =
        "org.zkoss.zel.BeanELResolver.CACHE_SIZE";

    static {
        if (System.getSecurityManager() == null) {
            CACHE_SIZE = Integer.parseInt(
                    System.getProperty(CACHE_SIZE_PROP, "1000"));
        } else {
            CACHE_SIZE = AccessController.doPrivileged(
                    new PrivilegedAction<Integer>() {

                    
                    public Integer run() {
                        return Integer.valueOf(
                                System.getProperty(CACHE_SIZE_PROP, "1000"));
                    }
                }).intValue();
        }
    }

    private final boolean readOnly;

    private final ConcurrentCache<String, BeanProperties> cache =
        new ConcurrentCache<String, BeanProperties>(CACHE_SIZE);

    public BeanELResolver() {
        this.readOnly = false;
    }

    public BeanELResolver(boolean readOnly) {
        this.readOnly = readOnly;
    }

    
    public Class<?> getType(ELContext context, Object base, Object property) {
        if (context == null) {
            throw new NullPointerException();
        }
        if (base == null || property == null) {
            return null;
        }

        context.setPropertyResolved(base, property);
        return this.property(context, base, property).getPropertyType();
    }

    
    public Object getValue(ELContext context, Object base, Object property) {
        if (context == null) {
            throw new NullPointerException();
        }
        if (base == null || property == null) {
            return null;
        }

        context.setPropertyResolved(base, property);
        Method m = this.property(context, base, property).read(context);
        try {
            Object value = null;
            Class<?> clazz = base.getClass();
            if(ProxyUtils.isSpringJdkDynamicProxy(clazz)|| Proxy.isProxyClass(clazz)) {
              InvocationHandler iv = Proxy.getInvocationHandler(base);
              value = iv.invoke(base, m, (Object[]) null);
            } else {
              value = m.invoke(base, (Object[]) null);
            }
            
            final Object result = value;
        	context.putContext(Method.class, m);
        	return result;
        } catch (InvocationTargetException e) {
            Throwable cause = e.getCause();
            Util.handleThrowable(cause);
            throw new ELException(Util.message(context, "propertyReadError",
                    base.getClass().getName(), property.toString()), cause);
        } catch (Throwable e) {
            throw new ELException(e);
        }
    }

    
    public void setValue(ELContext context, Object base, Object property,
            Object value) {
        if (context == null) {
            throw new NullPointerException();
        }
        if (base == null || property == null) {
            return;
        }

        context.setPropertyResolved(base, property);

        if (this.readOnly) {
            throw new PropertyNotWritableException(Util.message(context,
                    "resolverNotWriteable", base.getClass().getName()));
        }

        Method m = this.property(context, base, property).write(context);
        
      //for ZK-1178: check type of mehtod's parameter is the same as type of value
        //XXX refactored into write() ?
        if (!checkType(m, value)) {
        	Class<?> baseClass = ProxyUtils.getTargetClass(base);
    		//logic of propertySetterName is from java.beans.NameGenerator#capitalize()
    		//XXX the same in AstValue#setValue()
    		String propertySetterName = property.toString();
    		if(propertySetterName != null && propertySetterName.length()>0){
    			propertySetterName = 
    				"set"+ 
    				propertySetterName.substring(0,1).toUpperCase(Locale.ENGLISH) +
    				propertySetterName.substring(1);
    		}
    		////
    		
        	for (Method method : baseClass.getMethods()) {
        		//method name must the same as t.property (getter)
        		if (method.getName().equals(propertySetterName)) {
        			Class<?>[] clazzes = method.getParameterTypes();
        			if (clazzes.length!=1) { //not standard setter
        				break;
        			}
        			if (ClassUtil.isInstance(value, clazzes[0])) {
        				m = method;
        				break;
        			}
        		}
        	}        	
        }        
    	//// <=ZK-1178
        
        try {
            Class<?> clazz = base.getClass();
            if(ProxyUtils.isSpringJdkDynamicProxy(clazz)|| Proxy.isProxyClass(clazz)) {
              InvocationHandler iv = Proxy.getInvocationHandler(base);
              iv.invoke(base, m, new Object[] {value});
            } else {
              m.invoke(base, value);
            }
          
            context.putContext(Method.class, m);
        } catch (InvocationTargetException e) {
            Throwable cause = e.getCause();
            Util.handleThrowable(cause);
            throw new ELException(Util.message(context, "propertyWriteError",
                    base.getClass().getName(), property.toString()), cause);
        } catch (IllegalArgumentException e) {
        	throw new ELException(Util.message(context, "propertyWriteError",
                    new Object[] { base.getClass().getName(),
                            property.toString() }), e);
        } catch (Throwable e) {
            throw new ELException(e);
        }
    }
    
    private boolean checkType(Method m, Object value){
		Class<?>[] clazzes = m.getParameterTypes();
		if (clazzes.length!=1) { //not standard setter
			return false;
		}
		return clazzes[0].isInstance(value);
    }

    /**
     * @since EL 2.2
     */
    
    public Object invoke(ELContext context, Object base, Object method,
            Class<?>[] paramTypes, Object[] params) {
        if (context == null) {
            throw new NullPointerException();
        }
        if (base == null || method == null) {
            return null;
        }

        ExpressionFactory factory = ExpressionFactory.newInstance();
        
        String methodName = (String) factory.coerceToType(method, String.class);

		// Find the matching method
		Method matchingMethod = null;
		Class<?> clazz = ProxyUtils.getTargetClass(base);
		if (paramTypes != null) {
			try {
				matchingMethod = Util.getMethod(clazz, clazz.getMethod(methodName, paramTypes));
			} catch (NoSuchMethodException e) {
				//throw new MethodNotFoundException(e);
				int paramCount = 0;
				if (params != null) {
					paramCount = params.length;
				}
				Method[] methods = clazz.getMethods();
				for (Method m : methods) {
					if (methodName.equals(m.getName())) {
						if (m.getParameterTypes().length == paramCount) {
							// Same number of parameters - use the first match
							matchingMethod = Util.getMethod(clazz, m);
							break;
						}
						if (m.isVarArgs()
								&& paramCount > m.getParameterTypes().length - 2) {
							matchingMethod = Util.getMethod(clazz, m);
						}
					}
				}
				if (matchingMethod == null) {
					//maybe other resolver can resolve, we shouldn't throw exception here
					return null;
				}
			}
		} else {
			int paramCount = 0;
			if (params != null) {
				paramCount = params.length;
			}
			Method[] methods = clazz.getMethods();
			for (Method m : methods) {
				if (methodName.equals(m.getName())) {
					if (m.getParameterTypes().length == paramCount) {
						// Same number of parameters - use the first match
						matchingMethod = Util.getMethod(clazz, m);
						break;
					}
					if (m.isVarArgs()
							&& paramCount > m.getParameterTypes().length - 2) {
						matchingMethod = Util.getMethod(clazz, m);
					}
				}
			}
			if (matchingMethod == null) {
				throw new MethodNotFoundException("Unable to find method ["
						+ methodName + "] with [" + paramCount + "] parameters");
			}
		}

        Class<?>[] parameterTypes = matchingMethod.getParameterTypes();
        Object[] parameters = null;
        if (parameterTypes.length > 0) {
            parameters = new Object[parameterTypes.length];
            @SuppressWarnings("null")  // params.length >= parameterTypes.length
            int paramCount = params.length;
            if (matchingMethod.isVarArgs()) {
            	int varArgIndex = parameterTypes.length - 1;
                // First argCount-1 parameters are standard
                for (int i = 0; (i < varArgIndex); i++) {
                    parameters[i] = factory.coerceToType(params[i],
                            parameterTypes[i]);
                }
                // Last parameter is the varargs
                Class<?> varArgClass =
                    parameterTypes[varArgIndex].getComponentType();
                final Object varargs = Array.newInstance(
                    varArgClass,
                    (paramCount - varArgIndex));
                for (int i = (varArgIndex); i < paramCount; i++) {
                    Array.set(varargs, i - varArgIndex,
                            factory.coerceToType(params[i], varArgClass));
                }
                parameters[varArgIndex] = varargs;
                
            } else {
                parameters = new Object[parameterTypes.length];
            	try {
	                for (int i = 0; i < parameterTypes.length; i++) {
	                    parameters[i] = factory.coerceToType(params[i],
	                            parameterTypes[i]);
	                }
            	} catch (org.zkoss.zel.ELException ele) { 
            		//20110826, henrichen: could have located a method with wrong parameter types
            		if (paramTypes == null && ele.getCause() instanceof IllegalArgumentException) {
            			paramTypes = new Class<?>[parameters.length];
    	                for (int i = 0; i < parameterTypes.length; i++) {
    	                    paramTypes[i] = params[i].getClass();
    	                }
    	                return invoke(context, base, method, paramTypes, params);
            		}
            		throw ele;
            	}
            }
        }
        Object result = null;
		try {
  		  Class<?> baseClazz = base.getClass();
            if(ProxyUtils.isSpringJdkDynamicProxy(baseClazz)|| Proxy.isProxyClass(baseClazz)) {
              InvocationHandler iv = Proxy.getInvocationHandler(base);
              result = iv.invoke(base, matchingMethod, parameters);
            } else {
              result = matchingMethod.invoke(base, parameters);
            }
		} catch (IllegalArgumentException e) {
			throw new ELException(e);
		} catch (IllegalAccessException e) {
			throw new ELException(e);
		} catch (InvocationTargetException e) {
			Throwable cause = e.getCause();
			if (cause instanceof ThreadDeath) {
				throw (ThreadDeath) cause;
			}
			if (cause instanceof VirtualMachineError) {
				throw (VirtualMachineError) cause;
			}
			throw new ELException(cause);
		} catch (Throwable e) {
          throw new ELException(e);
        }
        
        context.setPropertyResolved(true);
        return result;  
    }

    
    public boolean isReadOnly(ELContext context, Object base, Object property) {
        if (context == null) {
            throw new NullPointerException();
        }
        if (base == null || property == null) {
            return false;
        }

        context.setPropertyResolved(base, property);
        return this.readOnly || this.property(context, base, property).isReadOnly();
    }

    
    public Iterator<FeatureDescriptor> getFeatureDescriptors(ELContext context, Object base) {
        if (base == null) {
            return null;
        }

        try {
            BeanInfo info = Introspector.getBeanInfo(base.getClass());
            PropertyDescriptor[] pds = info.getPropertyDescriptors();
            for (int i = 0; i < pds.length; i++) {
            	//20110927, henrichen: Instrospector see getAbc(int) as IndexedPropertyDescriptor
            	final PropertyDescriptor pd = pds[i]; 
                pd.setValue(RESOLVABLE_AT_DESIGN_TIME, Boolean.TRUE);
                if (pd instanceof IndexedPropertyDescriptor) {
                	pd.setValue(TYPE, ((IndexedPropertyDescriptor) pd).getIndexedPropertyType());
                } else {
                	pd.setValue(TYPE, pd.getPropertyType());
                }
            }
            return Arrays.asList((FeatureDescriptor[]) pds).iterator();
        } catch (IntrospectionException e) {
            //
        }

        return null;
    }

    
    public Class<?> getCommonPropertyType(ELContext context, Object base) {
        if (base != null) {
            return Object.class;
        }

        return null;
    }

    static final class BeanProperties {
        private final Map<String, BeanProperty> properties;

        private final Class<?> type;

        public BeanProperties(Class<?> type) throws ELException {
            this.type = type;
            this.properties = new HashMap<String, BeanProperty>();
            try {
                BeanInfo info = Introspector.getBeanInfo(this.type);
                PropertyDescriptor[] pds = info.getPropertyDescriptors();
                //20110927, henrichen: Introspector sees getAbc(int) as IndexedPropertyDescriptor
                //which might override getAbc() PropertyDescriptor; have to recover the case
                for (int i = 0; i < pds.length; i++) {
                	final PropertyDescriptor pd = recoverIndexedPropertyDescriptor(this.type, pds[i]);
                    this.properties.put(pd.getName(), new BeanProperty(type, pd));
                }
            } catch (IntrospectionException ie) {
                throw new ELException(ie);
            }
        }
        
        //20110927, henrichen: Introspector see getAbc(int) as IndexedPropertyDescriptor
        //which could merge away getAbc() PropertyDescriptor; have to recover this case
        //e.g. Map AbstractComponent#getAttributes() and Map AbstractComponent#getAttributes(int scope)
        private PropertyDescriptor recoverIndexedPropertyDescriptor(Class baseClz, PropertyDescriptor pd) {
        	if (pd instanceof IndexedPropertyDescriptor) {
        		final IndexedPropertyDescriptor ipd = (IndexedPropertyDescriptor) pd;
        		if (ipd.getIndexedReadMethod() != null) {
	            	try {
	            		//try to get getter parameter type 
	            		final String name = ipd.getName();
	            		final Method rm = ipd.getIndexedReadMethod();
	            		final String readMethodName = rm != null ? rm.getName() : null;
	            		final Method wm = ipd.getIndexedWriteMethod();
	            		final String writeMethodName = wm != null ? wm.getName() : null;
						pd = new PropertyDescriptor(name, baseClz, readMethodName, writeMethodName);
					} catch (IntrospectionException e) {
						//ignore
	            	} catch (SecurityException e) {
						//ignore
					}
        		}
        	}
        	return pd;
        }

        private BeanProperty get(ELContext ctx, String name) {
            BeanProperty property = this.properties.get(name);
            if (property == null) {
                throw new PropertyNotFoundException(Util.message(ctx,
                        "propertyNotFound", type.getName(), name));
            }
            return property;
        }

        public BeanProperty getBeanProperty(String name) {
            return get(null, name);
        }

        private Class<?> getType() {
            return type;
        }
    }

    static final class BeanProperty {
        private final Class<?> type;

        private final Class<?> owner;

        private final PropertyDescriptor descriptor;

        private Method read;

        private Method write;

        public BeanProperty(Class<?> owner, PropertyDescriptor descriptor) {
            this.owner = owner;
            this.descriptor = descriptor;
            //20110927, henrichen: Introspector see getAbc(int) as IndexedPropertyDescriptor
            this.type = descriptor instanceof IndexedPropertyDescriptor ?  
            		((IndexedPropertyDescriptor)descriptor).getIndexedPropertyType() :
            		descriptor.getPropertyType();
        }

        // Can't use Class<?> because API needs to match specification
        @SuppressWarnings("rawtypes")
        public Class getPropertyType() {
            return this.type;
        }

        public boolean isReadOnly() {
            return this.write == null &&
                    (null == (this.write = Util.getMethod(this.owner, descriptor.getWriteMethod())));
        }

        public Method getWriteMethod() {
            return write(null);
        }

        public Method getReadMethod() {
            return this.read(null);
        }

        private Method write(ELContext ctx) {
            if (this.write == null) {
                this.write = Util.getMethod(this.owner, descriptor.getWriteMethod());
                
                //20110921, henrichen: Introspector is too strict for Setter(must return void), here we loosen it
                if (this.write == null) {
                	final String name = this.descriptor.getName();
            		final String mname = "set" + Character.toUpperCase(name.charAt(0)) + name.substring(1);
            		//try to get setter parameter type 
            		final Class parameterTypes = this.descriptor instanceof IndexedPropertyDescriptor ?
            				((IndexedPropertyDescriptor)this.descriptor).getIndexedPropertyType() :
            				this.descriptor.getPropertyType();
            				
                	try {
                		final Method m = this.owner.getMethod(mname, new Class[] {parameterTypes});
                		this.write = Util.getMethod(this.owner, m);
                	} catch (SecurityException e) {
						//ignore
					} catch (NoSuchMethodException e) {
						//ignore
					}
                	
                	//20120423, dennis: Introspector is too strict for Setter(must has same class argument as getter), 
                    //here we chosen a possible method by the getter type
                    if (this.write == null) {
                    	try {
                    		final Method m = ClassUtil.getCloseMethod(this.owner, mname, new Class[]{parameterTypes});
                    		this.write = Util.getMethod(this.owner, m);
                    	} catch (SecurityException e) {
    						//ignore
    					} catch (NoSuchMethodException e) {
    						//ignore
						}
                    }
                }
                
                if (this.write == null) {
                    throw new PropertyNotWritableException(Util.message(ctx,
                            "propertyNotWritable", new Object[] {
                                    owner.getName(), descriptor.getName() }));
                }
            }
            return this.write;
        }

        private Method read(ELContext ctx) {
            if (this.read == null) {
                this.read = Util.getMethod(this.owner, descriptor.getReadMethod());
                if (this.read == null) {
                    throw new PropertyNotFoundException(Util.message(ctx,
                            "propertyNotReadable", new Object[] {
                                    owner.getName(), descriptor.getName() }));
                }
            }
            return this.read;
        }
    }

    private final BeanProperty property(ELContext ctx, Object base,
            Object property) {
        Class<?> type = ProxyUtils.getTargetClass(base);
        String prop = property.toString();

        BeanProperties props = this.cache.get(type.getName());
        if (props == null || type != props.getType()) {
            props = new BeanProperties(type);
            this.cache.put(type.getName(), props);
        }

        return props.get(ctx, prop);
    }

    private static final class ConcurrentCache<K,V> {

        private final int size;
        private final Map<K,V> eden;
        private final Map<K,V> longterm;

        public ConcurrentCache(int size) {
            this.size = size;
            this.eden = new ConcurrentHashMap<K, V>(size);
            this.longterm = new WeakHashMap<K, V>(size);
        }

        public V get(K key) {
            V value = this.eden.get(key);
            if (value == null) {
                synchronized (longterm) {
                    value = this.longterm.get(key);
                }
                if (value != null) {
                    this.eden.put(key, value);
                }
            }
            return value;
        }

        public void put(K key, V value) {
            if (this.eden.size() >= this.size) {
                synchronized (longterm) {
                    this.longterm.putAll(this.eden);
                }
                this.eden.clear();
            }
            this.eden.put(key, value);
        }

    }
}
