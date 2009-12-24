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
package org.apache.sling.scripting.scala.engine;

import static org.apache.sling.scripting.scala.engine.ExceptionHelper.initCause;

import java.io.File;
import java.io.Reader;
import java.net.URISyntaxException;
import java.net.URL;
import java.net.URLClassLoader;
import java.util.ArrayList;
import java.util.Arrays;

import javax.jcr.Item;
import javax.jcr.Node;
import javax.jcr.RepositoryException;
import javax.jcr.Session;
import javax.script.ScriptContext;
import javax.script.ScriptEngine;
import javax.script.ScriptException;

import org.apache.sling.jcr.api.SlingRepository;
import org.apache.sling.scripting.api.AbstractScriptEngineFactory;
import org.apache.sling.scripting.api.AbstractSlingScriptEngine;
import org.apache.sling.scripting.scala.interpreter.BundleFS;
import org.apache.sling.scripting.scala.interpreter.JcrFS;
import org.apache.sling.scripting.scala.interpreter.ScalaInterpreter;
import org.osgi.framework.Bundle;
import org.osgi.service.component.ComponentContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import scala.tools.nsc.Settings;
import scala.tools.nsc.io.AbstractFile;
import scala.tools.nsc.io.PlainFile;

/**
 * @scr.component
 *   label="Apache Sling Scala Script Handler"
 *   description="Adds Scala scripting support for rendering response content."
 *
 * @scr.service
 */
public class ScalaScriptEngineFactory extends AbstractScriptEngineFactory {
    private static final Logger log = LoggerFactory.getLogger(ScalaScriptEngineFactory.class);

    private static final String PATH_SEPARATOR = System.getProperty("path.separator");

    public final static String[] SCALA_SCRIPT_EXTENSIONS = {"scala", "scs"};
    public final static String SCALA_MIME_TYPE = "application/x-scala";
    public final static String SHORT_NAME = "scala";
    public final static String VERSION = "2.7.7";

    /**
     * @scr.property
     *   value="/var/classes"
     *   label="Compiler output directory"
     *   description="Output directory for files generated by the Scala compiler. Defaults to /var/classes."
     */
    public static final String OUT_DIR = "scala.compiler.outdir";
    private String outDir;

    /** @scr.reference */
    private SlingRepository repository;

    private ComponentContext context;
    private ScalaScriptEngine scriptEngine;

    public ScalaScriptEngineFactory() {
        super();
        setExtensions(SCALA_SCRIPT_EXTENSIONS);
        setMimeTypes(SCALA_MIME_TYPE);
        setNames(SHORT_NAME);
    }

    protected void activate(ComponentContext context) {
        this.context = context;
        outDir = (String) context.getProperties().get(OUT_DIR);
    }

    protected void deactivate(ComponentContext context) {
        scriptEngine = null;
        this.context = null;
    }

    public ScriptEngine getScriptEngine(){
        if (context == null) {
            throw new IllegalStateException("Bundle not activated");
        }

        if (scriptEngine == null) {
            final String path = getOutDir();
            try {
                Bundle[] bundles = context.getBundleContext().getBundles();
                Settings settings = createSettings(bundles);
                scriptEngine = new ScalaScriptEngine(
                        new ScalaInterpreter(
                            settings,
                            new LogReporter(
                                    LoggerFactory.getLogger(ScalaInterpreter.class),
                                    settings),
                            createClassPath(bundles),
                            createFolder(path)),
                        this);
            }
            catch (final Exception e) {
                return new AbstractSlingScriptEngine(this) {
                    public Object eval(Reader reader, ScriptContext context) throws ScriptException {
                        throw initCause(new ScriptException("Cannot access output directory: " + path), e);
                    }
                };
            }
        }
        return scriptEngine;
    }

    public String getLanguageName(){
        return SHORT_NAME;
    }

    public String getLanguageVersion(){
        return VERSION;
    }

    public String getOutDir() {
        return outDir;
    }

    // -----------------------------------------------------< private >---

    private static Settings createSettings(Bundle[] bundles) {
        Settings settings = new Settings();
        URL[] bootUrls = getBootUrls(bundles[0]);
        StringBuilder bootPath = new StringBuilder(settings.classpath().v());
        for (int k = 0; k < bootUrls.length; k++) {
            // bootUrls are sometimes null, at least when running integration
            // tests with cargo-maven2-plugin
            if(bootUrls[k] != null) {
                bootPath.append(PATH_SEPARATOR).append(bootUrls[k].getPath());
            }
        }

        settings.classpath().v_$eq(bootPath.toString());
        return settings;
    }

    private static AbstractFile[] createClassPath(Bundle[] bundles) {
        AbstractFile[] bundleFs = new AbstractFile[bundles.length];
        for (int k = 0; k < bundles.length; k++) {
            URL url = bundles[k].getResource("/");
            if (url == null) {
                url = bundles[k].getResource("");
            }

            if (url != null) {
                if ("file".equals(url.getProtocol())) {
                    try {
                        bundleFs[k] = new PlainFile(new File(url.toURI()));
                    }
                    catch (URISyntaxException e) {
                        throw initCause(new IllegalArgumentException("Can't determine url of bundle " + k), e);
                    }
                }
                else {
                    bundleFs[k] = BundleFS.create(bundles[k]);
                }
            }
            else {
                log.warn("Cannot retreive resources from Bundle {}. Skipping.", bundles[k].getSymbolicName());
            }
        }
        return bundleFs;
    }

    private AbstractFile createFolder(String path) throws Exception {
        Session session = repository.loginAdministrative(null);
        try {
            Node node = deepCreateNode(path, session, "sling:Folder");
            if(node == null) {
            	throw new Exception("Unable to create node " + path);
            }
            return JcrFS.create(node);
        } finally {
        	if(session != null) {
        		session.logout();
        	}
        }
    }

    private Node deepCreateNode(String path, Session session, String nodeType) throws RepositoryException {
    	Node result = null;
    	if(session.itemExists(path)) {
    		final Item it = session.getItem(path);
    		if(it.isNode()) {
    			result = (Node)it;
    		}
    	} else {
    		final int slashPos = path.lastIndexOf("/");
    		final String parentPath = path.substring(0, slashPos);
    		final String childPath = path.substring(slashPos + 1);
    		result = deepCreateNode(parentPath, session, nodeType).addNode(childPath, nodeType);
    		session.save();
    	}
    	return result;
    }

    private static URL[] getBootUrls(Bundle bundle) {
        ArrayList<URL> urls = new ArrayList<URL>();
        ClassLoader classLoader = bundle.getClass().getClassLoader();
        while (classLoader != null) {
            if (classLoader instanceof URLClassLoader) {
                urls.addAll(Arrays.asList(((URLClassLoader) classLoader).getURLs()));
            }
            classLoader = classLoader.getParent();
        }

        return urls.toArray(new URL[urls.size()]);
    }

}


