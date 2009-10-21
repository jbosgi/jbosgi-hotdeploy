/*
 * JBoss, Home of Professional Open Source
 * Copyright 2005, JBoss Inc., and individual contributors as indicated
 * by the @authors tag. See the copyright.txt in the distribution for a
 * full listing of individual contributors.
 *
 * This is free software; you can redistribute it and/or modify it
 * under the terms of the GNU Lesser General Public License as
 * published by the Free Software Foundation; either version 2.1 of
 * the License, or (at your option) any later version.
 *
 * This software is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the GNU
 * Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public
 * License along with this software; if not, write to the Free
 * Software Foundation, Inc., 51 Franklin St, Fifth Floor, Boston, MA
 * 02110-1301 USA, or see the FSF site: http://www.fsf.org.
 */
package org.jboss.osgi.hotdeploy.internal;

//$Id$

import static org.jboss.osgi.spi.OSGiConstants.OSGI_HOME;

import java.io.File;
import java.net.MalformedURLException;
import java.net.URL;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.jboss.osgi.deployment.common.Deployment;
import org.jboss.osgi.deployment.deployer.DeployerService;
import org.jboss.osgi.deployment.scanner.DeploymentScannerService;
import org.osgi.framework.Bundle;
import org.osgi.framework.BundleContext;
import org.osgi.framework.ServiceReference;
import org.osgi.framework.Version;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The DeploymentScanner service
 * 
 * @author thomas.diesler@jboss.com
 * @since 27-May-2009
 */
public class DeploymentScannerImpl implements DeploymentScannerService
{
   private Logger log = LoggerFactory.getLogger(DeploymentScannerImpl.class);
   private BundleContext context;

   private long scanInterval;
   private File scanLocation;
   private long scanCount;
   private long beforeStart;
   private long lastChange;

   private DeployerService deployer;
   private ScannerThread scannerThread;
   private List<Deployment> lastScan = new ArrayList<Deployment>();
   private Map<String, Deployment> deploymentCache = new HashMap<String, Deployment>();

   public DeploymentScannerImpl(BundleContext context)
   {
      this.context = context;

      // Get the DeployerService
      ServiceReference sref = context.getServiceReference(DeployerService.class.getName());
      deployer = (DeployerService)context.getService(sref);

      initScanner(context);
   }

   public long getScanCount()
   {
      return scanCount;
   }

   public long getScanInterval()
   {
      return scanInterval;
   }

   public URL getScanLocation()
   {
      return toURL(scanLocation.getAbsolutePath());
   }

   public long getLastChange()
   {
      return lastChange;
   }

   public void start()
   {
      String osgiHome = System.getProperty(OSGI_HOME);
      String scandir = scanLocation.getAbsolutePath();
      if (scandir.startsWith(osgiHome))
         scandir = "..." + scandir.substring(osgiHome.length());

      log.info("Start DeploymentScanner: [scandir=" + scandir + ",interval=" + scanInterval + "ms]");
      scannerThread = new ScannerThread(context, this);
      lastChange = System.currentTimeMillis();
      scannerThread.start();
   }

   public void stop()
   {
      if (scannerThread != null)
      {
         log.info("Stop DeploymentScanner");
         scannerThread.stopScan();
         scannerThread = null;
      }
   }

   public void scan()
   {
      List<Deployment> currScan = Arrays.asList(getBundleDeployments());

      logBundleDeployments("Current Scan", currScan);

      int oldDiff = processOldDeployments(currScan);
      int newDiff = processNewDeployments(currScan);

      if (oldDiff + newDiff > 0)
         lastChange = System.currentTimeMillis();

      lastScan = currScan;
      scanCount++;

      float diff = (lastChange - beforeStart) / 1000f;
      if (scanCount == 1)
         log.info("JBossOSGi Runtime started in " + diff + "sec");
   }

   private void logBundleDeployments(String message, List<Deployment> bundleDeps)
   {
      if (log.isTraceEnabled())
      {
         log.trace(message);
         for (Deployment dep : bundleDeps)
         {
            log.trace("   " + dep);
         }
      }
   }

   private int processOldDeployments(List<Deployment> currScan)
   {
      List<Deployment> diff = new ArrayList<Deployment>();

      // Detect OLD bundles that are not in the current scan  
      for (Deployment dep : lastScan)
      {
         if (currScan.contains(dep) == false)
         {
            Bundle bundle = getBundle(dep);
            if (bundle == null)
            {
               deploymentCache.remove(dep.getLocation().toExternalForm());
            }
            else
            {
               int state = bundle.getState();
               if (state == Bundle.INSTALLED || state == Bundle.RESOLVED || state == Bundle.ACTIVE)
               {
                  deploymentCache.remove(dep.getLocation().toExternalForm());
                  diff.add(dep);
               }
            }
         }
      }

      logBundleDeployments("OLD diff", diff);

      // Undeploy the bundles through the DeployerService
      if (diff.size() > 0)
      {
         try
         {
            Deployment[] depArr = diff.toArray(new Deployment[diff.size()]);
            deployer.undeploy(depArr);
         }
         catch (Exception ex)
         {
            log.error("Cannot undeploy bundles", ex);
         }
      }

      return diff.size();
   }

   private int processNewDeployments(List<Deployment> currScan)
   {
      List<Deployment> diff = new ArrayList<Deployment>();

      // Detect NEW bundles that are not in the last scan  
      for (Deployment dep : currScan)
      {
         if (lastScan.contains(dep) == false && getBundle(dep) == null)
         {
            diff.add(dep);
         }
      }

      logBundleDeployments("NEW diff", diff);

      // Deploy the bundles through the DeployerService
      if (diff.size() > 0)
      {
         try
         {
            Deployment[] depArr = diff.toArray(new Deployment[diff.size()]);
            deployer.deploy(depArr);
         }
         catch (Exception ex)
         {
            log.error("Cannot deploy bundles", ex);
         }
      }

      return diff.size();
   }

   public Deployment[] getBundleDeployments()
   {
      List<Deployment> bundles = new ArrayList<Deployment>();

      File[] listFiles = scanLocation.listFiles();
      if (listFiles == null)
         log.warn("Cannot list files in: " + scanLocation);

      if (listFiles != null)
      {
         for (File file : listFiles)
         {
            URL bundleURL = toURL(file);
            Deployment dep = deploymentCache.get(bundleURL.toExternalForm());
            if (dep == null)
            {
               // hot-deploy bundles are started automatically
               dep = deployer.createDeployment(bundleURL);
               dep.setAutoStart(true);

               deploymentCache.put(bundleURL.toExternalForm(), dep);
            }
            bundles.add(dep);
         }
      }

      Deployment[] arr = new Deployment[bundles.size()];
      return bundles.toArray(arr);
   }

   private void initScanner(BundleContext context)
   {
      scanInterval = 2000;
      beforeStart = System.currentTimeMillis();

      String interval = context.getProperty(PROPERTY_SCAN_INTERVAL);
      if (interval != null)
         scanInterval = new Long(interval);

      String scanLoc = context.getProperty(PROPERTY_SCAN_LOCATION);
      if (scanLoc == null)
         throw new IllegalStateException("Cannot obtain value for property: '" + PROPERTY_SCAN_LOCATION + "'");

      // Check if the prop is already an URL
      try
      {
         URL scanURL = new URL(scanLoc);
         scanLocation = new File(scanURL.getPath());
      }
      catch (MalformedURLException ex)
      {
         // ignore
      }

      // Check if the prop is an existing dir
      File scanFile = new File(scanLoc);
      if (scanFile.exists() == false)
         throw new IllegalStateException("Scan location does not exist: " + scanLoc);

      // Check if the scan location is a directory
      if (scanFile.isDirectory() == false)
         throw new IllegalStateException("Scan location is not a directory: " + scanLoc);

      scanLocation = scanFile;
   }

   private Bundle getBundle(Deployment dep)
   {
      String symbolicName = dep.getSymbolicName();
      Version version = Version.parseVersion(dep.getVersion());

      Bundle bundle = null;
      for (Bundle aux : context.getBundles())
      {
         if (aux.getSymbolicName().equals(symbolicName))
         {
            Version auxVersion = aux.getVersion();
            if (version.equals(auxVersion))
            {
               bundle = aux;
               break;
            }
         }
      }
      return bundle;
   }

   private URL toURL(File file)
   {
      try
      {
         return file.toURL();
      }
      catch (MalformedURLException ex)
      {
         throw new IllegalArgumentException("Invalid URL: " + file);
      }
   }

   private URL toURL(String urlStr)
   {
      try
      {
         return new URL(urlStr);
      }
      catch (MalformedURLException ex)
      {
         throw new IllegalArgumentException("Invalid URL: " + urlStr);
      }
   }
}