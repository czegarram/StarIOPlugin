package fr.sellsy.cordova;

import org.apache.cordova.CordovaPlugin;
import org.apache.cordova.CallbackContext;

import java.io.UnsupportedEncodingException;
import java.util.Locale;
import java.util.ArrayList;
import java.util.List;

import com.starmicronics.stario.PortInfo;
import com.starmicronics.stario.StarIOPort;
import com.starmicronics.stario.StarIOPortException;
import com.starmicronics.stario.StarPrinterStatus;

import org.apache.cordova.PluginResult;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import android.content.Context;
import android.util.Log;

/**
 * This class echoes a string called from JavaScript.
 */
public class StarIOPlugin extends CordovaPlugin {

  @Override
  public boolean execute(String action, JSONArray args, CallbackContext callbackContext) throws JSONException {
    if (action.equals("checkStatus")) {
      String portName = args.getString(0);
      this.checkStatus(portName, "mini", callbackContext);

      return true;
    }else if (action.equals("portDiscovery")) {
      String port = args.getString(0);
      this.portDiscovery(port, callbackContext);

      return true;
    }else {
      String portName = args.getString(0);
      String receipt = args.getString(1);

      return this.printReceipt(portName, "mini", receipt, callbackContext);
    }
  }

  public void checkStatus(String portName, String portSettings, CallbackContext callbackContext) {
    final Context context = this.cordova.getActivity();
    final CallbackContext callback = callbackContext;

    final String _portName = portName;
    final String _portSettings = portSettings;

    cordova.getThreadPool().execute(new Runnable() {
      public void run() {
        StarIOPort port = null;

        try {
          port = StarIOPort.getPort(_portName, _portSettings, 30000, context);

          // A sleep is used to get time for the socket to completely open
          try {
            Thread.sleep(600);
          } catch (InterruptedException e) {
          }

          StarPrinterStatus status;
          status = port.retreiveStatus();

          JSONObject json = new JSONObject();
          try {
            json.put("offline", status.offline);
            json.put("coverOpen", status.coverOpen);
            json.put("cutterError", status.cutterError);
            json.put("receiptPaperEmpty", status.receiptPaperEmpty);
          } catch (JSONException ex) {
          } finally {
            callback.success(json);
          }
        } catch (StarIOPortException e) {
          callback.error("Failed to connect to printer :" + e.getMessage());
        } finally {

          if (port != null) {
            try {
              StarIOPort.releasePort(port);
            } catch (StarIOPortException e) {
              callback.error("Failed to connect to printer" + e.getMessage());
            }
          }
        }
      }
    });
  }

  private void portDiscovery(String strInterface, CallbackContext callbackContext) {
    final CallbackContext callback = callbackContext;
    JSONArray result = new JSONArray();

    try {
      if (strInterface.equals("LAN")) {
        result = getPortDiscovery("LAN");
      } else if (strInterface.equals("Bluetooth")) {
        result = getPortDiscovery("Bluetooth");
      } else if (strInterface.equals("USB")) {
        result = getPortDiscovery("USB");
      } else {
        result = getPortDiscovery("All");
      }
    } catch (StarIOPortException exception) {
      callback.error(exception.getMessage());
    } catch (JSONException e) {
    } finally {
      Log.d("Discovered ports", result.toString());
      callback.success(result);
    }
  }

  private JSONArray getPortDiscovery(String interfaceName) throws StarIOPortException, JSONException {
    List<PortInfo> BTPortList;
    List<PortInfo> TCPPortList;
    List<PortInfo> USBPortList;

    final Context context = this.cordova.getActivity();
    final ArrayList<PortInfo> arrayDiscovery = new ArrayList<PortInfo>();

    JSONArray arrayPorts = new JSONArray();


    if (interfaceName.equals("Bluetooth") || interfaceName.equals("All")) {
      BTPortList = StarIOPort.searchPrinter("BT:");

      for (PortInfo portInfo : BTPortList) {
        arrayDiscovery.add(portInfo);
      }
    }
    if (interfaceName.equals("LAN") || interfaceName.equals("All")) {
      TCPPortList = StarIOPort.searchPrinter("TCP:");

      for (PortInfo portInfo : TCPPortList) {
        arrayDiscovery.add(portInfo);
      }
    }
    if (interfaceName.equals("USB") || interfaceName.equals("All")) {
      USBPortList = StarIOPort.searchPrinter("USB:", context);

      for (PortInfo portInfo : USBPortList) {
        arrayDiscovery.add(portInfo);
      }
    }

    for (PortInfo discovery : arrayDiscovery) {
      JSONObject port = new JSONObject();

      port.put("name", discovery.getPortName());

      if (!discovery.getMacAddress().equals("")) {
        port.put("macAddress", discovery.getMacAddress());

        if (!discovery.getModelName().equals("")) {
          port.put("modelName", discovery.getModelName());
        }
      } else if (interfaceName.equals("USB") || interfaceName.equals("All")) {
        if (!discovery.getModelName().equals("")) {
          port.put("modelName", discovery.getModelName());
        }
        if (!discovery.getUSBSerialNumber().equals(" SN:")) {
          port.put("USBSerialNumber", discovery.getUSBSerialNumber());
        }
      }
      arrayPorts.put(port);
    }

    return arrayPorts;
  }

  private String getPortSettingsOption(String portName) {
    String portSettings = "";

    if (portName.toUpperCase(Locale.US).startsWith("TCP:")) {
      portSettings += ""; // retry to yes
    } else if (portName.toUpperCase(Locale.US).startsWith("BT:")) {
      portSettings += ";p"; // or ";p"
      portSettings += ";l"; // standard
    }

    return portSettings;
  }


  private boolean printReceipt(String portName, String portSettings, String receipt, CallbackContext callbackContext) throws JSONException {
    Context context = this.cordova.getActivity();
    ArrayList<byte[]> list = new ArrayList<byte[]>();

    // list.add(new byte[] { 0x1b, 0x1d, 0x74, (byte)0x80 });
    list.add(createCpUTF8(receipt));
    list.add(new byte[] { 0x1b, 0x69, 0x00, 0x00 });
    list.add(new byte[] { 0x1b, 0x64, 0x02 }); // Cut
    list.add(new byte[] { 0x07 }); // Kick cash drawer

    return sendCommand(context, portName, portSettings, list, callbackContext);
  }

  private boolean sendCommand(Context context, String portName, String portSettings, ArrayList<byte[]> byteList, CallbackContext callbackContext) {
    final CallbackContext callback = callbackContext;
    JSONObject json = new JSONObject();
    StarIOPort port = null;
    boolean error = false;

    try {
      /*
       * using StarIOPort3.1.jar (support USB Port) Android OS Version: upper 2.2
       */
      port = StarIOPort.getPort(portName, portSettings, 30000, context);
      try {
        Thread.sleep(800);
      } catch (InterruptedException e) {
      }
      /*
       * Using Begin / End Checked Block method When sending large amounts of raster data,
       * adjust the value in the timeout in the "StarIOPort.getPort" in order to prevent
       * "timeout" of the "endCheckedBlock method" while a printing.
       *
       * If receipt print is success but timeout error occurs(Show message which is "There
       * was no response of the printer within the timeout period." ), need to change value
       * of timeout more longer in "StarIOPort.getPort" method.
       * (e.g.) 10000 -> 30000
       */
      StarPrinterStatus status = port.beginCheckedBlock();

      if (status.offline == true) {
        //throw new StarIOPortException("A printer is offline");
        json.put("offline", status.offline);
      } else {
        byte[] commandToSendToPrinter = convertFromListByteArrayTobyteArray(byteList);

        port.writePort(commandToSendToPrinter, 0, commandToSendToPrinter.length);
        port.setEndCheckedBlockTimeoutMillis(30000);// Change the timeout time of endCheckedBlock method.

        status = port.endCheckedBlock();

        if (status.offline == true) {
          json.put("offline", status.offline);
        }
        if (status.coverOpen == true) {
          json.put("coverOpen", status.coverOpen);
        }
        if (status.cutterError == true) {
          json.put("cutterError", status.cutterError);
        }
        if (status.receiptPaperEmpty == true) {
          json.put("receiptPaperEmpty", status.receiptPaperEmpty);
        }
      }
    } catch (StarIOPortException e) {
      error = true;

      try {
        json.put("error", true);
      } catch (JSONException je) {
      }
    } catch (JSONException e) {
      error = true;

      try {
        json.put("error", true);
      } catch (JSONException je) {
      }
    } finally {
      if (port != null) {
        try {
          StarIOPort.releasePort(port);
        } catch (StarIOPortException e) {
        }
      }
      callback.success(json);

      return error;
    }
  }

  private byte[] createCpUTF8(String inputText) {
    byte[] byteBuffer = null;

    try {
      byteBuffer = inputText.getBytes("UTF-8");
    } catch (UnsupportedEncodingException e) {
      byteBuffer = inputText.getBytes();
    }

    return byteBuffer;
  }


  private byte[] convertFromListByteArrayTobyteArray(List<byte[]> ByteArray) {
    int dataLength = 0;
    for (int i = 0; i < ByteArray.size(); i++) {
      dataLength += ByteArray.get(i).length;
    }

    int distPosition = 0;
    byte[] byteArray = new byte[dataLength];
    for (int i = 0; i < ByteArray.size(); i++) {
      System.arraycopy(ByteArray.get(i), 0, byteArray, distPosition, ByteArray.get(i).length);
      distPosition += ByteArray.get(i).length;
    }

    return byteArray;
  }
}
