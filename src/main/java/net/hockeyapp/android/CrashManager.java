package net.hockeyapp.android;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.FilenameFilter;
import java.io.IOException;
import java.io.InputStreamReader;
import java.lang.Thread.UncaughtExceptionHandler;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import net.hockeyapp.android.internal.ExceptionHandler;
import net.hockeyapp.android.internal.Strings;

import org.apache.http.NameValuePair;
import org.apache.http.client.entity.UrlEncodedFormEntity;
import org.apache.http.client.methods.HttpPost;
import org.apache.http.impl.client.DefaultHttpClient;
import org.apache.http.message.BasicNameValuePair;
import org.apache.http.protocol.HTTP;

import android.app.AlertDialog;
import android.content.Context;
import android.content.DialogInterface;
import android.content.SharedPreferences;
import android.content.SharedPreferences.Editor;
import android.util.Log;

public class CrashManager {
  private static String identifier = null;
  private static String urlString = null;

  // Public Methods

  /**
   * Register new crash manager. The crash manager sets an exception 
   * handler to catch all unhandled exceptions. The handler writes the
   * stack trace and additional meta data to a file. If it finds one or
   * more of these files at the next start, it shows an alert dialog
   * to ask the user if he want the send the crash data to HockeyApp.
   * 
   * @param context Parent activity.
   * @param appIdentifier App ID of your app on HockeyApp.
   */
  public static void register(Context context, String appIdentifier) {
    register(context, Constants.BASE_URL, appIdentifier, null);
  }

  /**
   * Register new crash manager. The crash manager sets an exception 
   * handler to catch all unhandled exceptions. The handler writes the
   * stack trace and additional meta data to a file. If it finds one or
   * more of these files at the next start, it shows an alert dialog
   * to ask the user if he want the send the crash data to HockeyApp.
   * 
   * @param context Parent activity.
   * @param appIdentifier App ID of your app on HockeyApp.
   * @param listener Implement for callback functions.
   */
  public static void register(Context context, String appIdentifier, CrashManagerListener listener) {
    register(context, Constants.BASE_URL, appIdentifier, listener);
  }

  /**
   * Register new crash manager. The crash manager sets an exception 
   * handler to catch all unhandled exceptions. The handler writes the
   * stack trace and additional meta data to a file. If it finds one or
   * more of these files at the next start, it shows an alert dialog
   * to ask the user if he want the send the crash data to HockeyApp.
   * 
   * @param context Parent activity.
   * @param urlString URL of your private QuincyKit server.
   * @param appIdentifier App ID of your app on HockeyApp.
   * @param listener Implement for callback functions.
   */
  public static void register(Context context, String urlString, String appIdentifier, CrashManagerListener listener) {
    CrashManager.urlString = urlString;
    CrashManager.identifier = appIdentifier;

    Constants.loadFromContext(context);
    
    if (CrashManager.identifier == null) {
      CrashManager.identifier = Constants.APP_PACKAGE;
    }
    
    Boolean ignoreDefaultHandler = (listener != null) && (listener.ignoreDefaultHandler());
    int foundOrSend = hasStackTraces(context);
    if (foundOrSend == 1) {
      Boolean autoSend = false;
      if (listener != null) {
        autoSend = listener.onCrashesFound();
      }
      
      if (!autoSend) {
        showDialog(context, listener, ignoreDefaultHandler);
      }
      else {
        sendCrashes(context, listener, ignoreDefaultHandler);
      }
    }
    else if (foundOrSend == 2) {
      sendCrashes(context, listener, ignoreDefaultHandler);
    }
    else {
      registerHandler(context, listener, ignoreDefaultHandler);
    }
  }

  // Private Methods

  private static void showDialog(final Context context, final CrashManagerListener listener, final boolean ignoreDefaultHandler) {
    if (context == null) {
      return;
    }

    AlertDialog.Builder builder = new AlertDialog.Builder(context);
    builder.setTitle(Strings.get(listener, Strings.CRASH_DIALOG_TITLE_ID));
    builder.setMessage(Strings.get(listener, Strings.CRASH_DIALOG_MESSAGE_ID));

    builder.setNegativeButton(Strings.get(listener, Strings.CRASH_DIALOG_NEGATIVE_BUTTON_ID), new DialogInterface.OnClickListener() {
      public void onClick(DialogInterface dialog, int which) {
        deleteStackTraces(context);
        registerHandler(context, listener, ignoreDefaultHandler);
      } 
    });

    builder.setPositiveButton(Strings.get(listener, Strings.CRASH_DIALOG_POSITIVE_BUTTON_ID), new DialogInterface.OnClickListener() {
      public void onClick(DialogInterface dialog, int which) {
        sendCrashes(context, listener, ignoreDefaultHandler);
      } 
    });

    builder.create().show();
  }
  
  private static void sendCrashes(final Context context, final CrashManagerListener listener, final boolean ignoreDefaultHandler) {
    saveConfirmedStackTraces(context);
    
    new Thread() {
      @Override
      public void run() {
        submitStackTraces(context, listener);
        registerHandler(context, listener, ignoreDefaultHandler);
      }
    }.start();
  }

  private static void registerHandler(Context context, CrashManagerListener listener, boolean ignoreDefaultHandler) {
    // Get current handler
    UncaughtExceptionHandler currentHandler = Thread.getDefaultUncaughtExceptionHandler();
    if (currentHandler != null) {
      Log.d(Constants.TAG, "Current handler class = " + currentHandler.getClass().getName());
    }

    // Register if not already registered
    if (!(currentHandler instanceof ExceptionHandler)) {
      Thread.setDefaultUncaughtExceptionHandler(new ExceptionHandler(currentHandler, listener, ignoreDefaultHandler));
    }
  }

  private static String getURLString() {
    return urlString + "api/2/apps/" + identifier + "/crashes/";      
  }

  public static void deleteStackTraces(Context context) {
    Log.d(Constants.TAG, "Looking for exceptions in: " + Constants.FILES_PATH);
    String[] list = searchForStackTraces();

    if ((list != null) && (list.length > 0)) {
      Log.d(Constants.TAG, "Found " + list.length + " stacktrace(s).");

      for (int index = 0; index < list.length; index++) {
        try {
          Log.d(Constants.TAG, "Delete stacktrace " + list[index] + ".");
          deleteStackTrace(context, list[index]);
          context.deleteFile(list[index]);
        } 
        catch (Exception e) {
          e.printStackTrace();
        }
      }
    }
  }
  
  private static void deleteStackTrace(Context context, String filename) {
    context.deleteFile(filename);
    
    String user = filename.replace(".stacktrace", ".user");
    context.deleteFile(user);
    
    String contact = filename.replace(".stacktrace", ".contact");
    context.deleteFile(contact);
    
    String description = filename.replace(".stacktrace", ".description");
    context.deleteFile(description);
  }

  public static void submitStackTraces(Context context, CrashManagerListener listener) {
    Log.d(Constants.TAG, "Looking for exceptions in: " + Constants.FILES_PATH);
    String[] list = searchForStackTraces();
    Boolean successful = false;

    if ((list != null) && (list.length > 0)) {
      Log.d(Constants.TAG, "Found " + list.length + " stacktrace(s).");

      for (int index = 0; index < list.length; index++) {
        try {
          // Read contents of stack trace
          String filename = list[index];
          String stacktrace = contentsOfFile(context, filename);
          if (stacktrace.length() > 0) {
            // Transmit stack trace with POST request
            Log.d(Constants.TAG, "Transmitting crash data: \n" + stacktrace);
            DefaultHttpClient httpClient = new DefaultHttpClient(); 
            HttpPost httpPost = new HttpPost(getURLString());
            
            List <NameValuePair> parameters = new ArrayList <NameValuePair>(); 
            parameters.add(new BasicNameValuePair("raw", stacktrace));
            parameters.add(new BasicNameValuePair("userID", contentsOfFile(context, filename.replace(".stacktrace", ".user"))));
            parameters.add(new BasicNameValuePair("contact", contentsOfFile(context, filename.replace(".stacktrace", ".contact"))));
            parameters.add(new BasicNameValuePair("description", contentsOfFile(context, filename.replace(".stacktrace", ".description"))));
            parameters.add(new BasicNameValuePair("sdk", Constants.SDK_NAME));
            parameters.add(new BasicNameValuePair("sdk_version", Constants.SDK_VERSION));
            
            httpPost.setEntity(new UrlEncodedFormEntity(parameters, HTTP.UTF_8));
            
            httpClient.execute(httpPost);   
            successful = true;
          }
        }
        catch (Exception e) {
          e.printStackTrace();
        } 
        finally {
          if (successful) {
            deleteStackTrace(context, list[index]);

            if (listener != null) {
              listener.onCrashesSent();
            }
          }
          else {
            if (listener != null) {
              listener.onCrashesNotSent();
            }
          }
        }
      }
    }
  } 

  private static String contentsOfFile(Context context, String filename) {
    StringBuilder contents = new StringBuilder();
    BufferedReader reader = null;
    try {
      reader = new BufferedReader(new InputStreamReader(context.openFileInput(filename)));
      String line = null;
      while ((line = reader.readLine()) != null) {
        contents.append(line);
        contents.append(System.getProperty("line.separator"));
      }
    }
    catch (FileNotFoundException e) {
    }
    catch (IOException e) {
      e.printStackTrace();
    }
    finally {
      if (reader != null) {
        try { 
          reader.close(); 
        } 
        catch (IOException ignored) { 
        }
      }
    }
    
    return contents.toString();
  }
  
  public static void saveConfirmedStackTraces(Context context) {
    try {
      String[] filenames = searchForStackTraces();
      SharedPreferences preferences = context.getSharedPreferences("HockeySDK", Context.MODE_PRIVATE);
      Editor editor = preferences.edit();
      editor.putString("ConfirmedFilenames", joinArray(filenames, "|"));
      editor.commit();
    }
    catch (Exception e) {
      // Just in case, we catch all exceptions here
    }
  }
  
  private static String joinArray(String[] array, String delimiter) {
    StringBuffer buffer = new StringBuffer();
    for (int index = 0; index < array.length; index++) {
      buffer.append(array[index]);
      if (index < array.length - 1) {
        buffer.append(delimiter);
      }
    }
    return buffer.toString();
  }

  public static int hasStackTraces(Context context) {
    String[] filenames = searchForStackTraces();
    List<String> confirmedFilenames = null;
    int result = 0;
    if (filenames.length > 0) {
      try {
        SharedPreferences preferences = context.getSharedPreferences("HockeySDK", Context.MODE_PRIVATE);
        confirmedFilenames = Arrays.asList(preferences.getString("ConfirmedFilenames", "").split("\\|"));
      }
      catch (Exception e) {
        // Just in case, we catch all exceptions here
      }

      if (confirmedFilenames != null) {
        result = 2;
        
        for (String filename : filenames) {
          if (!confirmedFilenames.contains(filename)) {
            result = 1;
            break;
          }
        }
      }
      else {
        result = 1;
      }
    }

    return result;
  }

  private static String[] searchForStackTraces() {
    // Try to create the files folder if it doesn't exist
    File dir = new File(Constants.FILES_PATH + "/");
    dir.mkdir();

    // Filter for ".stacktrace" files
    FilenameFilter filter = new FilenameFilter() { 
      public boolean accept(File dir, String name) {
        return name.endsWith(".stacktrace"); 
      } 
    }; 
    return dir.list(filter); 
  }
}
