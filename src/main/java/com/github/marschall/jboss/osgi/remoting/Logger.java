package com.github.marschall.jboss.osgi.remoting;

import org.osgi.framework.BundleContext;
import org.osgi.service.log.LogService;
import org.osgi.util.tracker.ServiceTracker;

import static org.osgi.service.log.LogService.LOG_ERROR;
import static org.osgi.service.log.LogService.LOG_WARNING;

/**
 * A wrapper around OSGi log service that does nothing if none is present.
 * 
 * <p>
 * The implementation tries to delay class loading as long as possible so that
 * we shouldn't run into a {@link NoClassDefFoundError} when OSGi log service
 * is not present.
 * </p>
 * 
 * <p>
 * Log levels should be compile time constants and therefore be inlined so that
 * they don't trigger class loading.
 * </p>
 */
final class Logger {
  
  private ServiceTracker<?, ?> serviceTracker;
  
  Logger(BundleContext bundleContext) {
    this.serviceTracker = new ServiceTracker(bundleContext, "org.osgi.service.log.LogService", null);
    this.serviceTracker.open();
  }
  
  void stop() {
    this.serviceTracker.close();
  }
  
  void error(String message, Throwable cause) {
    this.log(LOG_ERROR, message, cause);
  }
  
  void warning(String message, Throwable cause) {
    this.log(LOG_WARNING, message, cause);
  }
  
  private void log(int level, String message, Throwable cause) {
    Object service = this.serviceTracker.getService();
    if (service != null) {
      this.doLog(service, level, message, cause);
    }
  }

  private void doLog(Object service, int level, String message, Throwable cause) {
    // delay class loading for as long as possible
    LogService logService = (LogService) service;
    logService.log(level, message, cause);
    
  }
  
  void warning(String message) {
    this.log(LOG_WARNING, message);
  }
  
  private void log(int level, String message) {
    Object service = this.serviceTracker.getService();
    if (service != null) {
      this.doLog(service, level, message);
    }
  }
  
  private void doLog(Object service, int level, String message) {
    // delay class loading for as long as possible
    LogService logService = (LogService) service;
    logService.log(level, message);
  }
  

}
