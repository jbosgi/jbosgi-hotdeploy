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

import static org.jboss.osgi.spi.Constants.OSGI_HOME;

import java.io.File;
import java.net.MalformedURLException;
import java.net.URL;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.jboss.osgi.common.log.LogServiceTracker;
import org.jboss.osgi.spi.service.DeployerService;
import org.jboss.osgi.spi.service.DeploymentScannerService;
import org.jboss.osgi.spi.util.BundleDeployment;
import org.jboss.osgi.spi.util.BundleDeploymentFactory;
import org.osgi.framework.Bundle;
import org.osgi.framework.BundleContext;
import org.osgi.framework.BundleException;
import org.osgi.framework.ServiceReference;
import org.osgi.framework.Version;
import org.osgi.service.log.LogService;

/**
 * The DeploymentScanner service
 * 
 * @author thomas.diesler@jboss.com
 * @since 27-May-2009
 */
public class DeploymentScannerImpl implements DeploymentScannerService
{
   private LogServiceTracker log;
   private BundleContext context;

   private long scanInterval;
   private File scanLocation;
   private long scanCount;
   private long lastChange;

   private DeployerService deployer;
   private ScannerThread scannerThread;
   private List<BundleDeployment> lastScan = new ArrayList<BundleDeployment>();
   private Set<ScanListener> listeners = new LinkedHashSet<ScanListener>();
   private Map<String, BundleDeployment> deploymentCache = new HashMap<String, BundleDeployment>();
   private boolean traceBundles = false;

   public DeploymentScannerImpl(BundleContext context)
   {
      this.log = new LogServiceTracker(context);
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
      
      log.log(LogService.LOG_INFO, "Start DeploymentScanner: [scandir=" + scandir + ",interval=" + scanInterval + "ms]");
      scannerThread = new ScannerThread(context, this);
      lastChange = System.currentTimeMillis();
      scannerThread.start();
   }

   public void stop()
   {
      if (scannerThread != null)
      {
         log.log(LogService.LOG_INFO, "Stop DeploymentScanner");
         scannerThread.stopScan();
         scannerThread = null;
      }
   }

   public void addScanListener(ScanListener listener)
   {
      listeners.add(listener);
   }

   public void removeScanListener(ScanListener listener)
   {
      listeners.remove(listener);
   }

   public void scan()
   {
      // Use a copy so listeners can remove themselves from within the callback
      List<ScanListener> scanListeners = new ArrayList<ScanListener>(listeners);
      for (ScanListener listener : scanListeners)
         listener.beforeScan(this);
      
      List<BundleDeployment> currScan = Arrays.asList(getBundleDeployments());

      if (traceBundles)
         logBundleDeployments("Current Scan", currScan);

      int oldDiff = processOldDeployments(currScan);
      int newDiff = processNewDeployments(currScan);

      if (oldDiff + newDiff > 0)
         lastChange = System.currentTimeMillis();
      
      lastScan = currScan;
      scanCount++;

      for (ScanListener listener : scanListeners)
         listener.afterScan(this);
   }

   private void logBundleDeployments(String message, List<BundleDeployment> bundleDeps)
   {
      System.out.println(message);
      for (BundleDeployment dep : bundleDeps)
      {
         System.out.println("   " + dep);
      }
   }

   private int processOldDeployments(List<BundleDeployment> currScan)
   {
      List<BundleDeployment> diff = new ArrayList<BundleDeployment>();

      // Detect OLD bundles that are not in the current scan  
      for (BundleDeployment dep : lastScan)
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

      if (traceBundles)
         logBundleDeployments("OLD diff", diff);
      
      // Undeploy the bundles through the DeployerService
      try
      {
         BundleDeployment[] depArr = diff.toArray(new BundleDeployment[diff.size()]);
         deployer.undeploy(depArr);
      }
      catch (Exception ex)
      {
         log.log(LogService.LOG_ERROR, "Cannot undeploy bundles", ex);
      }
      
      return diff.size();
   }

   private int processNewDeployments(List<BundleDeployment> currScan)
   {
      List<BundleDeployment> diff = new ArrayList<BundleDeployment>();

      // Detect NEW bundles that are not in the last scan  
      for (BundleDeployment dep : currScan)
      {
         if (lastScan.contains(dep) == false && getBundle(dep) == null)
         {
            diff.add(dep);
         }
      }

      if (traceBundles)
         logBundleDeployments("NEW diff", diff);

      // Deploy the bundles through the DeployerService
      try
      {
         BundleDeployment[] depArr = diff.toArray(new BundleDeployment[diff.size()]);
         deployer.deploy(depArr);
      }
      catch (Exception ex)
      {
         log.log(LogService.LOG_ERROR, "Cannot deploy bundles", ex);
      }
      
      return diff.size();
   }

   public BundleDeployment[] getBundleDeployments()
   {
      List<BundleDeployment> bundles = new ArrayList<BundleDeployment>();
      
      File[] listFiles = scanLocation.listFiles();
      if (listFiles == null)
         log.log(LogService.LOG_WARNING, "Cannot list files in: " + scanLocation);
         
      if (listFiles != null)
      {
         for (File file : listFiles)
         {
            URL bundleURL = toURL(file);
            BundleDeployment dep = deploymentCache.get(bundleURL.toExternalForm());
            if (dep == null)
            {
               try
               {
                  // hot-deploy bundles are started automatically
                  dep = BundleDeploymentFactory.createBundleDeployment(bundleURL);
                  dep.setAutoStart(true);
                  
                  deploymentCache.put(bundleURL.toExternalForm(), dep);
               }
               catch (BundleException ex)
               {
                  log.log(LogService.LOG_WARNING, "Cannot obtain bundle deployment for: " + file);
               }
            }
            bundles.add(dep);
         }
      }
      
      BundleDeployment[] arr = new BundleDeployment[bundles.size()];
      return bundles.toArray(arr);
   }
   
   private void initScanner(BundleContext context)
   {
      scanInterval = 2000;

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

   private Bundle getBundle(BundleDeployment dep)
   {
      String symbolicName = dep.getSymbolicName();
      Version version = dep.getVersion();

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