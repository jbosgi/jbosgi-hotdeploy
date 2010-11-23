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

import static org.jboss.osgi.spi.OSGiConstants.OSGI_HOME;

import java.io.File;
import java.net.MalformedURLException;
import java.net.URL;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import org.jboss.logging.Logger;
import org.jboss.osgi.hotdeploy.DeploymentScannerService;
import org.jboss.osgi.spi.util.StringPropertyReplacer;
import org.jboss.osgi.spi.util.SysPropertyActions;
import org.osgi.framework.Bundle;
import org.osgi.framework.BundleContext;

/**
 * The DeploymentScanner service
 *
 * @author thomas.diesler@jboss.com
 * @since 27-May-2009
 */
public class DeploymentScannerImpl implements DeploymentScannerService
{
   // Provide logging
   private static final Logger log = Logger.getLogger(DeploymentScannerImpl.class);

   private BundleContext context;

   private long scanInterval;
   private File scanLocation;
   private long scanCount;
   private long beforeStart;
   private long lastChange;

   private ScannerThread scannerThread;
   private List<URL> lastScan = new ArrayList<URL>();

   public DeploymentScannerImpl(BundleContext context)
   {
      this.context = context;
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
      String osgiHome = SysPropertyActions.getProperty(OSGI_HOME, null);
      String scandir = scanLocation.getAbsolutePath();
      if (osgiHome != null && scandir.startsWith(osgiHome))
         scandir = "..." + scandir.substring(osgiHome.length());

      log.infof("Start DeploymentScanner: [scandir=%s,interval=%dms]", scandir, scanInterval);
      scannerThread = new ScannerThread(context, this);
      lastChange = System.currentTimeMillis();
      scannerThread.start();
   }

   public void stop()
   {
      if (scannerThread != null)
      {
         log.infof("Stop DeploymentScanner");
         scannerThread.stopScan();
         scannerThread = null;
      }
   }

   public void scan()
   {
      List<URL> currScan = Arrays.asList(getBundleDeployments());

      logBundleDeployments("Current Scan", currScan);

      int oldDiff = processOldDeployments(currScan);
      int newDiff = processNewDeployments(currScan);

      if (oldDiff + newDiff > 0)
         lastChange = System.currentTimeMillis();

      lastScan = currScan;
      scanCount++;

      float diff = (lastChange - beforeStart) / 1000f;
      if (scanCount == 1)
         log.infof("JBossOSGi Runtime started in %fsec", diff);
   }

   private void logBundleDeployments(String message, List<URL> bundleDeps)
   {
      log.tracef(message);
      for (URL dep : bundleDeps)
      {
         log.tracef(" %s", dep);
      }
   }

   private int processOldDeployments(List<URL> currScan)
   {
      List<URL> diff = new ArrayList<URL>();

      // Detect OLD bundles that are not in the current scan
      for (URL url : lastScan)
      {
         if (currScan.contains(url) == false)
            diff.add(url);
      }

      logBundleDeployments("OLD diff", diff);

      // Undeploy the bundles
      for (URL url : diff)
      {
         Bundle bundle = getBundle(url);
         if (bundle != null)
         {
            try
            {
               bundle.uninstall();
            }
            catch (Exception ex)
            {
               log.errorf(ex, "Cannot undeploy bundle: %s", bundle);
            }
         }
      }

      return diff.size();
   }

   private int processNewDeployments(List<URL> currScan)
   {
      List<URL> diff = new ArrayList<URL>();

      // Detect NEW bundles that are not in the last scan
      for (URL url : currScan)
      {
         if (lastScan.contains(url) == false)
            diff.add(url);
      }

      logBundleDeployments("NEW diff", diff);

      // Install the bundles
      List<Bundle> bundles = new ArrayList<Bundle>(); 
      for (URL url : diff)
      {
         try
         {
            Bundle bundle = context.installBundle(url.toExternalForm());
            bundles.add(bundle);
         }
         catch (Exception ex)
         {
            log.errorf(ex, "Cannot deploy bundle: %s", url);
         }
      }
      
      // Start the bundles
      for (Bundle bundle : bundles)
      {
         try
         {
            bundle.start();
         }
         catch (Exception ex)
         {
            log.errorf(ex, "Cannot start bundle: %s", bundle);
         }
      }
      return diff.size();
   }

   public URL[] getBundleDeployments()
   {
      List<URL> bundles = new ArrayList<URL>();

      File[] listFiles = scanLocation.listFiles();
      if (listFiles == null)
         log.warnf("Cannot list files in: %s", scanLocation);

      if (listFiles != null)
      {
         for (File file : listFiles)
         {
            URL bundleURL = toURL(file);
            bundles.add(bundleURL);
         }
      }

      URL[] arr = new URL[bundles.size()];
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

      URL scanURL = toURL(scanLoc);
      File scanFile = new File(scanURL.getPath());

      // Check if the prop is an existing dir
      if (scanFile.exists() == false)
         throw new IllegalStateException("Scan location does not exist: " + scanURL);

      // Check if the scan location is a directory
      if (scanFile.isDirectory() == false)
         throw new IllegalStateException("Scan location is not a directory: " + scanURL);

      scanLocation = scanFile;
   }

   private Bundle getBundle(URL url)
   {
      Bundle bundle = null;
      String urlPath = url.getFile();
      for (Bundle aux : context.getBundles())
      {
         if (aux.getBundleId() != 0)
         {
            String location = aux.getLocation();
            String locationPath = toURL(location).getFile();
            if (urlPath.equals(locationPath))
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
         return file.getAbsoluteFile().toURI().toURL();
      }
      catch (MalformedURLException ex)
      {
         throw new IllegalArgumentException("Invalid URL: " + file);
      }
   }

   private URL toURL(String path)
   {
      URL pathURL = null;
      String realPath = StringPropertyReplacer.replaceProperties(path, context);
      try
      {
         pathURL = new URL(realPath);
      }
      catch (MalformedURLException ex)
      {
         // ignore
      }

      if (pathURL == null)
      {
         try
         {
            File file = new File(realPath);
            if (file.exists())
               pathURL = file.toURI().toURL();
         }
         catch (MalformedURLException ex)
         {
            throw new IllegalArgumentException("Invalid path: " + realPath, ex);
         }
      }

      if (pathURL == null)
         throw new IllegalArgumentException("Invalid path: " + realPath);

      return pathURL;
   }
}