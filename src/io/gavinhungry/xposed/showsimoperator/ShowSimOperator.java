package io.gavinhungry.xposed.showsimoperator;

import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.callbacks.XC_LoadPackage.LoadPackageParam;
import de.robv.android.xposed.XC_MethodHook;

import android.content.Context;
import android.app.AndroidAppHelper;
import android.telephony.SubscriptionInfo;
import android.telephony.SubscriptionManager;
import android.telephony.TelephonyManager;

import java.lang.reflect.Method;

public class ShowSimOperator implements IXposedHookLoadPackage {

  private static Context getContext() {
    return (Context) AndroidAppHelper.currentApplication();
  }

  private static TelephonyManager getTelephonyManager() {
    return (TelephonyManager) getContext().getSystemService(Context.TELEPHONY_SERVICE);
  }

  private static SubscriptionManager getSubscriptionManager() {
    return (SubscriptionManager) getContext().getSystemService(Context.TELEPHONY_SUBSCRIPTION_SERVICE);
  }

  /**
   * Reflect the overloaded TelephonyManager#getNetworkOperatorName that accepts a subId int
   *
   * @param subId subscription id
   * @return the network operator name for the subscription id (hooked, it returns the SIM operator name)
   */
  private static String getNetworkOperatorNameBySubId(int subId) throws Throwable {
    Method getNetworkOperatorName = XposedHelpers.findMethodExact(TelephonyManager.class.getName(), null, "getNetworkOperatorName", int.class);
    return (String) getNetworkOperatorName.invoke(getTelephonyManager(), subId);
  }

  /**
   * Get the original network operator name (as if TelephonyManager#getNetworkOperatorName were unhooked)
   *
   * @param subId subscription id
   * @return the original network operator name
   */
  private static String getRealNetworkOperatorNameBySubId(int subId) throws Throwable {
    Method getPhoneId = XposedHelpers.findMethodExact(SubscriptionManager.class.getName(), null, "getPhoneId", int.class);
    int phoneId = (Integer) getPhoneId.invoke(getSubscriptionManager(), subId);

    Class<?> TelephonyProperties = XposedHelpers.findClass("com.android.internal.telephony.TelephonyProperties", null);
    String PROPERTY_OPERATOR_ALPHA = (String) XposedHelpers.getStaticObjectField(TelephonyProperties, "PROPERTY_OPERATOR_ALPHA");

    Method getTelephonyProperty = XposedHelpers.findMethodExact(TelephonyManager.class.getName(), null, "getTelephonyProperty", int.class, String.class, String.class);
    return (String) getTelephonyProperty.invoke(getTelephonyManager(), phoneId, PROPERTY_OPERATOR_ALPHA, "");
  }

  @Override
  public void handleLoadPackage(final LoadPackageParam lpparam) throws Throwable {

    XposedHelpers.findAndHookMethod(TelephonyManager.class.getName(), lpparam.classLoader,
      "getNetworkOperatorName", int.class, new XC_MethodHook() {

      @Override
      protected void afterHookedMethod(final MethodHookParam param) throws Throwable {
        int subId = (Integer) param.args[0];
        String result = (String) param.getResult();

        if (getRealNetworkOperatorNameBySubId(subId).equals(result)) {
          String simOperatorName = (String) XposedHelpers.callMethod(param.thisObject, "getSimOperatorNameForSubscription", subId);
          param.setResult(simOperatorName);
        }
      }
    });

    XposedHelpers.findAndHookMethod(SubscriptionInfo.class.getName(), lpparam.classLoader,
      "getCarrierName", new XC_MethodHook() {

      @Override
      protected void afterHookedMethod(final MethodHookParam param) throws Throwable {
        String result = (String) param.getResult();
        int subId = (Integer) XposedHelpers.callMethod(param.thisObject, "getSubscriptionId");

        if (getRealNetworkOperatorNameBySubId(subId).equals(result)) {
          String simOperatorName = getNetworkOperatorNameBySubId(subId);
          param.setResult(simOperatorName);
        }
      }
    });

    final Class<?> MobileSignalController = XposedHelpers.findClass("com.android.systemui.statusbar.policy.MobileSignalController", lpparam.classLoader);
    XposedBridge.hookAllMethods(MobileSignalController, "updateNetworkName", new XC_MethodHook() {

      @Override
      protected void afterHookedMethod(final MethodHookParam param) throws Throwable {
        Object mCurrentState = XposedHelpers.getObjectField(param.thisObject, "mCurrentState");
        String networkName = (String) XposedHelpers.getObjectField(mCurrentState, "networkName");

        SubscriptionInfo mSubscriptionInfo = (SubscriptionInfo) XposedHelpers.getObjectField(param.thisObject, "mSubscriptionInfo");
        int subId = mSubscriptionInfo.getSubscriptionId();

        if (getRealNetworkOperatorNameBySubId(subId).equals(networkName)) {
          String simOperatorName = getNetworkOperatorNameBySubId(subId);
          XposedHelpers.setObjectField(mCurrentState, "networkName", simOperatorName);
        }
      }
    });

  }
}
