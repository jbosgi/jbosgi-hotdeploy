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

import org.jboss.osgi.common.log.LogServiceTracker;
import org.jboss.osgi.spi.service.DeploymentScannerService;
import org.osgi.framework.BundleContext;
import org.osgi.service.log.LogService;

/**
 * A simple scanner thread
 * 
 * @author thomas.diesler@jboss.com
 * @since 27-May-2009
 */
public class ScannerThread extends Thread
{
   private LogService log;
   
   private DeploymentScannerService scanner;
   private boolean active = true;

   public ScannerThread(BundleContext context, DeploymentScannerService scanner)
   {
      this.log = new LogServiceTracker(context);
      this.scanner = scanner;
   }

   @Override
   public void run()
   {
      try
      {
         while (active == true)
         {
            try
            {
               scanner.scan();
            }
            catch (RuntimeException ex)
            {
               log.log(LogService.LOG_ERROR, "Deployment error", ex);
            }
            
            // Sleep for the duration of the configured interval 
            sleep(scanner.getScanInterval());
         }
      }
      catch (InterruptedException ex)
      {
         // ignore
      }
   }

   public void stopScan()
   {
      active = false;
      interrupt();
   }
}