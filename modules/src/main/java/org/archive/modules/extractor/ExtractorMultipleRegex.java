/*
 *  This file is part of the Heritrix web crawler (crawler.archive.org).
 *
 *  Licensed to the Internet Archive (IA) by one or more individual 
 *  contributors. 
 *
 *  The IA licenses this file to You under the Apache License, Version 2.0
 *  (the "License"); you may not use this file except in compliance with
 *  the License.  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 */
package org.archive.modules.extractor;

import groovy.text.SimpleTemplateEngine;
import groovy.text.Template;

import java.io.IOException;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.regex.Matcher;

import org.apache.commons.httpclient.URIException;
import org.archive.io.ReplayCharSequence;
import org.archive.modules.CrawlURI;
import org.archive.modules.fetcher.FetchStatusCodes;
import org.archive.util.TextUtils;
import org.codehaus.groovy.control.CompilationFailedException;

public class ExtractorMultipleRegex extends Extractor {

    private static Logger LOGGER =
        Logger.getLogger(ExtractorMultipleRegex.class.getName());
    
    {
        setContentRegexes(new HashMap<String,String>());
    }
    public void setContentRegexes(Map<String, String> regexes) {
        kp.put("contentRegexes", regexes);
    }
    @SuppressWarnings("unchecked")
    public Map<String, String> getContentRegexes() {
        return (Map<String, String>) kp.get("contentRegexes");
    }
    
    {
        setTemplate("");
    }
    public String getTemplate() {
        return (String) kp.get("template");
    }
    public void setTemplate(String templ) {
        kp.put("template", templ);
    }
    
    
    {
        setUriRegex("");
    }
    public void setUriRegex(String reg) {
        kp.put("uriRegex", reg);
    }
    public String getUriRegex() {
        return (String) kp.get("uriRegex");
    }

    
    @Override
    protected boolean shouldProcess(CrawlURI uri) {
        if (uri.getContentLength() <= 0) {
            return false;
        }
        if (!getExtractorParameters().getExtract404s() 
                && uri.getFetchStatus()==FetchStatusCodes.S_NOT_FOUND) {
            return false; 
        }
        return true;
    }
    
    protected Template compileTemplate() {
        try {
            return new SimpleTemplateEngine().createTemplate(getTemplate());
        } catch (CompilationFailedException e) {
            LOGGER.log(Level.SEVERE, "problem with template", e);
            return null;
        } catch (ClassNotFoundException e) {
            LOGGER.log(Level.SEVERE, "problem with template", e);
            return null;
        } catch (IOException e) {
            LOGGER.log(Level.SEVERE, "script problem", e);
            return null;
        }
    }

    @Override
    public void extract(CrawlURI curi) {
        
        Matcher m = TextUtils.getMatcher(getUriRegex(), curi.getURI());
        if (!m.matches()) {
            return;
        }
        
        // our data structure to prepopulate with matches for nested iteration
        Map<String,List<List<String>>> allMatches = new LinkedHashMap<String, List<List<String>>>();
        
        List<String> uriRegexGroups = new LinkedList<String>();
        for(int i = 0; i <= m.groupCount(); i++) {
            uriRegexGroups.add(m.group(i));
        }
        List<List<String>> uriRegexMatchList = new LinkedList<List<String>>();
        uriRegexMatchList.add(uriRegexGroups);
        allMatches.put("uriRegex", uriRegexMatchList);

        ReplayCharSequence cs;
        try {
            cs = curi.getRecorder().getContentReplayCharSequence();
        } catch (IOException e) {
            curi.getNonFatalFailures().add(e);
            LOGGER.log(Level.WARNING, "Failed get of replay char sequence in "
                    + Thread.currentThread().getName(), e);
            return;
        }
        
        // the names for regexes given in the config
        Set<String> names = getContentRegexes().keySet();
        for (String patternName: names) {
            String regex = getContentRegexes().get(patternName);
            Matcher matcher = TextUtils.getMatcher(regex, cs);
            // populate the list of finds for this patternName
            List<List<String>> foundList = new LinkedList<List<String>>();
            while (matcher.find()) {
                LinkedList<String> groups = new LinkedList<String>();
                // include group 0, the full pattern match
                for (int i = 0; i <= matcher.groupCount(); i++) {
                    groups.add(matcher.group(i));
                }
                foundList.add(groups);
            }
            allMatches.put(patternName, foundList);
        }

        Template groovyTemplate = compileTemplate();
        if (groovyTemplate == null) {
            // already logged error
            return;
        }
        
        long i = 0;
        boolean done = false;
        while (!done) {
            long tmp = i;
            
            // bindings are the variables available to populate the template
            // { String patternName => List<String> groups }  
            Map<String,Object> bindings = new LinkedHashMap<String,Object>();
            String[] patternNames = allMatches.keySet().toArray(new String[0]);
            for (int j = 0; j < patternNames.length; j++) {
                List<List<String>> matchList = allMatches.get(patternNames[j]);
                
                if (j == patternNames.length - 1 && tmp >= matchList.size()) {
                    done = true;
                    break;
                }
                
                int index = (int) (tmp % matchList.size());
                bindings.put(patternNames[j], matchList.get(index));
                
                // make the index of this match available to the template as well
                bindings.put(patternNames[j] + "Index", index);
                
                tmp = tmp / matchList.size();
            }
            
            if (!done) {
                addOutlink(curi, groovyTemplate, bindings);
            }
            
            i++;
        }
    }
    
    protected void addOutlink(CrawlURI curi, Template groovyTemplate, Map<String, Object> bindings) {
        String outlinkUri = groovyTemplate.make(bindings).toString();
        
        try {
            Link.addRelativeToBase(curi, 
                    getExtractorParameters().getMaxOutlinks(), outlinkUri, 
                    HTMLLinkContext.INFERRED_MISC, Hop.INFERRED);
        } catch (URIException e) {
            logUriError(e, curi.getUURI(), outlinkUri);
        }
    }
    
}