package net.gotev.sipservice;

import com.crashlytics.android.Crashlytics;

import org.pjsip.pjsua2.Account;
import org.pjsip.pjsua2.CallOpParam;
import org.pjsip.pjsua2.OnIncomingCallParam;
import org.pjsip.pjsua2.OnRegStateParam;
import org.pjsip.pjsua2.pjsip_status_code;

import java.util.HashMap;
import java.util.Set;

/**
 * Wrapper around PJSUA2 Account object.
 * @author gotev (Aleksandar Gotev)
 */
public class SipAccount extends Account {

    private static final String LOG_TAG = SipAccount.class.getSimpleName();

    private HashMap<Integer, SipCall> activeCalls = new HashMap<>();
    private SipAccountData data;
    private SipService service;

    protected SipAccount(SipService service, SipAccountData data) {
        super();
        this.service = service;
        this.data = data;
    }

    public SipService getService() {
        return service;
    }

    public SipAccountData getData() {
        return data;
    }

    public void create() throws Exception {
        create(data.getAccountConfig());
    }

    protected void removeCall(int callId) {
        SipCall call = activeCalls.get(callId);

        if (call != null) {
            Logger.debug(LOG_TAG, "Removing call with ID: " + callId);
            activeCalls.remove(callId);
        }
    }

    public SipCall getCall(int callId) {
        return activeCalls.get(callId);
    }

    public Set<Integer> getCallIDs() {
        return activeCalls.keySet();
    }

    public SipCall addIncomingCall(int callId) {

        SipCall call = new SipCall(this, callId);
        activeCalls.put(callId, call);
        Logger.debug(LOG_TAG, "Added incoming call with ID " + callId + " to " + data.getIdUri());
        return call;
    }

    public SipCall addOutgoingCall(final String numberToDial) {
        SipCall call = new SipCall(this);

        CallOpParam callOpParam = new CallOpParam();
        try {
            if (numberToDial.startsWith("sip:")) {
                call.makeCall(numberToDial, callOpParam);
            } else {
                if ("*".equals(data.getRealm())) {
                    call.makeCall("sip:" + numberToDial, callOpParam);
                } else {
                    call.makeCall("sip:" + numberToDial + "@" + data.getRealm(), callOpParam);
                }
            }
            activeCalls.put(call.getId(), call);
            Logger.debug(LOG_TAG, "New outgoing call with ID: " + call.getId());

            return call;

        } catch (Exception exc) {
            Logger.error(LOG_TAG, "Error while making outgoing call", exc);
            return null;
        }
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;

        SipAccount that = (SipAccount) o;

        return data.equals(that.data);

    }

    @Override
    public int hashCode() {
        return data.hashCode();
    }

    @Override
    public void onRegState(OnRegStateParam prm) {
        service.getBroadcastEmitter()
               .registrationState(data.getIdUri(), prm.getCode().swigValue());

        if (!data.isPushDisabled()) {
            this.service.checkRegistrationTimeout(prm.getRdata().getWholeMsg(), data.getIdUri());
        }

    }

    @Override
    public void onIncomingCall(OnIncomingCallParam prm) {

        SipCall call = addIncomingCall(prm.getCallId());

        // Send 603 Decline whether there's an already ongoing call or we are in DND mode
        if (activeCalls.size() > 1 || service.isDND()) {
            try {
                CallerInfo contactInfo = new CallerInfo(call.getInfo());
                service.getBroadcastEmitter().missedCall(contactInfo.getDisplayName(), contactInfo.getRemoteUri());
                call.declineIncomingCall();
                Logger.debug(LOG_TAG, "sending busy to call ID: " + prm.getCallId());
            } catch(Exception ex) {
                Logger.error(LOG_TAG, "Error while getting missed call info", ex);
            }
            return;
        }

        try {
            // Answer with 180 Ringing
            CallOpParam callOpParam = new CallOpParam();
            callOpParam.setStatusCode(pjsip_status_code.PJSIP_SC_RINGING);
            call.answer(callOpParam);
            Logger.debug(LOG_TAG, "Sending 180 ringing");

            service.startRingtone();

            String displayName = "", remoteUri = "";
            try {
                CallerInfo contactInfo = new CallerInfo(call.getInfo());
                displayName = contactInfo.getDisplayName();
                remoteUri = contactInfo.getRemoteUri();
            } catch (Exception ex) {
                Logger.error(LOG_TAG, "Error while getting caller info", ex);
                Crashlytics.logException(ex);
            }

            service.getBroadcastEmitter()
                    .incomingCall(data.getIdUri(), prm.getCallId(),
                            displayName, remoteUri);

        } catch (Exception ex) {
            Logger.error(LOG_TAG, "Error while getting caller info", ex);
            Crashlytics.logException(ex);
        }
    }
}
