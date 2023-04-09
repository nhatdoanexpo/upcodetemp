package com.bot.service;

import com.bot.config.BrowserConfig;
import com.bot.model.DeviceProfile;
import com.bot.model.EmailProfile;
import com.bot.utils.ZipUtils;

import lombok.extern.java.Log;

import org.apache.commons.io.CopyUtils;
import org.apache.commons.lang3.StringUtils;
import org.openqa.selenium.JavascriptExecutor;
import org.openqa.selenium.WindowType;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Level;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
@Service
@Log
public class BrowserMonitor {
    ApiClient apiClient;
    final JobHandler jobHandler;
    final BrowserConfig browserConfig;

    private static final String ZALO_URL = "https://chat.zalo.me";

    private static final String YOUTUBE_URL = "https://youtube.com";
    
    private static final String ACTION_KEY = "postMessageKey";
    
    public String message = "";
    private final ConcurrentHashMap<String, AtomicBoolean> blockingKey = new ConcurrentHashMap<>();

    public BrowserMonitor(BrowserConfig browserConfig, JobHandler jobHandler) {
        this.browserConfig = browserConfig;
        this.jobHandler = jobHandler;
        apiClient= new ApiClient();
    }

    @Scheduled(fixedDelay = 500)
    void startBrowser() {
        try {
            var key = "LOCK";
            if (blockActionByKey(blockingKey, key)) {
                Executors.newSingleThreadExecutor().submit(() -> {
                    try {
                        this.handleBrowser();
                    } catch (Exception e) {
                        log.log(Level.WARNING, "BrowserMonitor >> startBrowser >> Exception:", e);
                    } finally {
                        blockingKey.remove(key);
                    }
                });
            }
        } catch (Exception e) {
            log.log(Level.WARNING, "BrowserMonitor >> startBrowser >> Exception:", e);
        }

    }


    void handleBrowser() throws IOException {
    	String profileTest = "user_999";
        var driver = browserConfig.getChromeDriver(profileTest);
        //
        String emailParent = System.getenv("EMAIL_PARENT");

        
        String listEmail = apiClient.getDataEmailProfile(); 
        // Sử dụng thư viện Gson để chuyển đổi chuỗi JSON thành danh sách đối tượng
        List<EmailProfile> myObjects = new Gson().fromJson(listEmail, new TypeToken<List<EmailProfile>>(){}.getType());
        
        Optional<EmailProfile> profileOk= myObjects.stream().filter(x -> StringUtils.isEmpty(x.getLastUpdate()) || ( TimeUnit.MILLISECONDS.toMinutes(Math.abs(new Date().getTime() - Long.parseLong(x.getLastUpdate()))))>2).findFirst();
        String linkProfile ="";
        if(profileOk.isPresent()) {
        	System.out.println("profileOk "+profileOk.toString());
        	     linkProfile = profileOk.get().getLinkProfile(); 
             	 String deviceProfile = apiClient.getDataDeviceProfile(); 
          	   
        	   if(StringUtils.isNotEmpty(deviceProfile)|| deviceProfile.length()>5) {
               List<DeviceProfile> listDevice = new Gson().fromJson(deviceProfile, new TypeToken<List<DeviceProfile>>(){}.getType());
               Optional<DeviceProfile> profileZaloOk = listDevice.stream().filter(x -> x.getChrome_id().equals(profileOk.get().getEmail())&& StringUtils.isNotEmpty(x.getLinkProfile())).findFirst();
                  if(profileZaloOk.isPresent()) {
                	  System.out.println("profileZaloOk"+profileZaloOk.toString());
                	  linkProfile = profileZaloOk.get().getLinkProfile();
                  }}
                  //tai profile ve
                String fileDownload =  apiClient.downloadFile(linkProfile);
                if(StringUtils.isNotEmpty(fileDownload)) { 
                	boolean unzip =  ZipUtils.unzip4jFile(fileDownload, "./browser-profile","nhat123");
                     if(unzip) {
                    	 boolean changeProfile = ZipUtils.copyFolder("./browser-profile/"+linkProfile.substring(0,linkProfile.lastIndexOf(".")), "./browser-profile/"+profileTest);
                   
                    	 if(changeProfile) {
                    		 driver.close();
                    	 }else {
                    		 System.out.println("changeProfile  fail" );
                    	 }
                     }
                }
                  
        }
        
     
        
        try {
            driver.manage().timeouts().pageLoadTimeout(Duration.ofSeconds(30));
            driver.get(ZALO_URL);
            driver.switchTo().newWindow(WindowType.WINDOW.TAB);
            driver.get(YOUTUBE_URL); 
            Thread.sleep(TimeUnit.SECONDS.toMillis(10));
            List<String> tabs =  new ArrayList(driver.getWindowHandles()); 
            
            if(tabs.size()>0) { 
            	tabs.forEach(tab ->{ 
            		driver.switchTo().window(tab);  
            		 String key = ACTION_KEY+tab;
            		  log.log(Level.INFO, "chay script addEventListener tab {0}", tab); 
            		  var js = (JavascriptExecutor) driver;
            		  log.log(Level.INFO, "url  tab {0}", driver.getCurrentUrl()); 
            		  
                      js.executeScript("localStorage.removeItem(arguments[0]);",key);
                      var jsScript = """
                              window.addEventListener('message', function(event) {
                                   var message = event.data;"""+
                                   "console.log('"+key+"',message); "+
                                   "localStorage.setItem('"+key+"', message);"+
                             "});";
                     
                      js.executeScript(jsScript,key,key); 
            		
            	}); 
            	
            	 
                while (!message.equals("exist")) {
                	int sizeOld = tabs.size();
                	 tabs =  new ArrayList(driver.getWindowHandles()); 
                	 int sizeNew = tabs.size();
                	 
                	 if(sizeOld != sizeNew) {
                		 log.log(Level.INFO, "có tab mới"); 
                		    
                	 }
                     if(tabs.size()>0) { 
                    		tabs.forEach(tab ->{ 
                    			 String key = ACTION_KEY+tab;
                    try {
						Thread.sleep(TimeUnit.SECONDS.toMillis(3));
					} catch (InterruptedException e) {
						// TODO Auto-generated catch block
						e.printStackTrace();
					}
                    driver.switchTo().window(tab);
                    log.log(Level.INFO, "url  tab {0}", driver.getCurrentUrl()); 
           		    
          		  var js1 = (JavascriptExecutor) driver;
                    var postMessage = (String) js1.executeScript("return window.localStorage.getItem(arguments[0]);",key);
                    message = StringUtils.defaultIfBlank(postMessage, "");
                    log.log(Level.INFO, "BrowserMonitor >> handleBrowser >> tab: {0}", tab);
                    log.log(Level.INFO, "BrowserMonitor >> handleBrowser >> receive message: {0}", message);
                    if (StringUtils.isNotBlank(message)) {
                        jobHandler.sendAction(driver, message,tab);
                    }
                    		});
                }
                }
             
            	
            	
            }
            
          

        } catch (Exception e) {
            log.log(Level.WARNING, "BrowserMonitor >> handleBrowser >> Exception:", e);
            Thread.currentThread().interrupt();
        } finally {
            driver.quit();
        }
    }


    public boolean blockActionByKey(Map<String, AtomicBoolean> trackingMap, String trackingKey) {
        var newBlock = new AtomicBoolean(true);
        var existingBlock = Optional.ofNullable(trackingMap.putIfAbsent(trackingKey, newBlock));
        if (existingBlock.isPresent()) {
            newBlock = existingBlock.get();
        }
        var result = newBlock;
        return result.compareAndSet(true, false);

    }

}
