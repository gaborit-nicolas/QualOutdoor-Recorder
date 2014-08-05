package com.qualoutdoor.recorder.home;

import java.util.List;

import android.app.Activity;
import android.content.res.Configuration;
import android.os.Bundle;
import android.support.v4.app.Fragment;
import android.telephony.CellInfo;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.ScrollView;
import android.widget.TableLayout;
import android.widget.TextView;

import com.qualoutdoor.recorder.R;
import com.qualoutdoor.recorder.ServiceListener;
import com.qualoutdoor.recorder.ServiceProvider;
import com.qualoutdoor.recorder.telephony.ICellInfo;
import com.qualoutdoor.recorder.telephony.ISignalStrength;
import com.qualoutdoor.recorder.telephony.TelephonyContext;
import com.qualoutdoor.recorder.telephony.TelephonyListener;
import com.qualoutdoor.recorder.telephony.TelephonyService;
import com.qualoutdoor.recorder.telephony.ViewCellInfo;

/**
 * This fragment displays the main informations of the phone state on a single
 * screen. Its parent activity must implements the interface TelephonyContext.
 */
public class NetworkFragment extends Fragment {

    /** The events monitored by the Telephony Listener */
    private static final int events = TelephonyListener.LISTEN_CELL_INFO
            | TelephonyListener.LISTEN_SIGNAL_STRENGTHS
            | TelephonyListener.LISTEN_DATA_STATE;
    /**
     * The Telephony Listener, which defines the behavior against telephony
     * state changes
     */
    private TelephonyListener telListener = new TelephonyListener() {
        public void onSignalStrengthsChanged(ISignalStrength signalStrength) {
            // Update the signal strength
            NetworkFragment.this.signalStrength = signalStrength;
            // Update the UI
            updateSignalStrengthView();
        };

        @Override
        public void onCellInfoChanged(List<ICellInfo> cellInfos) {
            Log.d("NetworkFragment", "OnCellInfoChanged");
            // Find the first registered cell
            for (ICellInfo cell : cellInfos) {
                if (cell.isRegistered()) {
                    // This is the primary cell
                    cellInfo = cell;
                    // Update the UI elements
                    updateCellInfo();
                    updateMCCView();
                    updateMNCView();
                    updateCIDView();
                    // Stop searching
                    break;
                }
            }
        }

        public void onDataStateChanged(int state, int networkType) {
            // Update the network type
            network = networkType;
            // Update the UI
            updateNetworkTypeView();
        };
    };

    /** The TelephonyService Provider given by the activity */
    private ServiceProvider<TelephonyService> telephonyService;
    /**
     * The service listener defines the behavior when the service becomes
     * available
     */
    private ServiceListener<TelephonyService> telServiceListener = new ServiceListener<TelephonyService>() {
        @Override
        public void onServiceAvailable(TelephonyService service) {
            // Register the telephony listener
            telephonyService.getService().listen(telListener, events);
        }
    };

    /** The signal strength value */
    private ISignalStrength signalStrength;
    /** The network type code */
    private int network;
    /** The primary cell */
    private ICellInfo cellInfo;

    /** The signal strength value text view */
    private TextView viewSignalStrength;
    /** The cell ID text view */
    private TextView viewCellId;
    /** The mobile network code text view */
    private TextView viewMobileNetworkCode;
    /** The network type code text view */
    private TextView viewNetworkType;
    /** The mobile country code text view */
    private TextView viewMobileCountryCode;
    /** The primary cell view */
    private ViewCellInfo viewCellInfo;
    /** The network type code strings */
    private String[] networkNames;

    @Override
    public void onCreate(Bundle savedInstanceState) {
        // Initialize the network names from the ressources
        networkNames = getResources().getStringArray(R.array.network_type_name);
        super.onCreate(savedInstanceState);
    }

    @Override
    public void onAttach(Activity activity) {
        super.onAttach(activity);
        try {
            // This cast makes sure that the container activity has implemented
            // TelephonyContext
            TelephonyContext telephonyContext = (TelephonyContext) getActivity();

            // Retrieve the service connection
            telephonyService = telephonyContext.getTelephonyServiceProvider();
        } catch (ClassCastException e) {
            throw new ClassCastException(activity.toString()
                    + " must implement " + TelephonyContext.class.toString());
        }
    }

    @Override
    public void onConfigurationChanged(Configuration newConfig) {
        Log.d("NetworkFragment", "onConfigurationChanged");

        super.onConfigurationChanged(newConfig);
    }

    @Override
    public View onCreateView(LayoutInflater inflater, ViewGroup container,
            Bundle savedInstanceState) {
        ScrollView view = (ScrollView) inflater.inflate(
                R.layout.fragment_network, container, false);
        // Initialize the views references
        viewSignalStrength = (TextView) view
                .findViewById(R.id.signal_strength_value);
        viewCellId = (TextView) view.findViewById(R.id.cell_id_value);
        viewMobileNetworkCode = (TextView) view
                .findViewById(R.id.mobile_network_code_value);
        viewNetworkType = (TextView) view
                .findViewById(R.id.network_type_code_value);
        viewMobileCountryCode = (TextView) view
                .findViewById(R.id.mobile_country_code_value);

        viewCellInfo = (ViewCellInfo) view.findViewById(R.id.fragment_network_cell_info);

        return view;
    }

    TableLayout table;
    Button button;

    @Override
    public void onResume() {
        // Tell we want to be informed when services become available
        telephonyService.register(telServiceListener);
        super.onStart();
    }

    @Override
    public void onPause() {
        // If needed unregister our telephony listener
        if (telephonyService.isAvailable()) {
            telephonyService.getService().listen(telListener,
                    TelephonyListener.LISTEN_NONE);
        }
        // Unregister the services listeners
        telephonyService.unregister(telServiceListener);
        super.onPause();
    }

    /** Update the text field with the current value of signal strength */
    private void updateSignalStrengthView() {
        // Check that the view has been initialized
        if (viewSignalStrength != null) {
            // Access the UI from the main thread
            getActivity().runOnUiThread(new Runnable() {
                @Override
                public void run() {
                    // Fill in the view fields values
                    viewSignalStrength.setText(signalStrength.getDbm()
                            + " (dBm)");
                    // Invalidate the view that changed
                    viewSignalStrength.invalidate();
                }
            });
        }
    }

    /** Update the text field with the current value of network type */
    private void updateNetworkTypeView() {
        // Check that the view has been initialized
        if (viewNetworkType != null) {
            // Access the UI from the main thread
            getActivity().runOnUiThread(new Runnable() {
                @Override
                public void run() {
                    viewNetworkType.setText(networkNames[network]);
                    // Invalidate the view that changed
                    viewNetworkType.invalidate();
                }
            });
        }
    }

    /** Update the text field with the current value of MNC */
    private void updateMNCView() {
        // Check that the view has been initialized
        if (viewMobileNetworkCode != null) {
            // Access the UI from the main thread
            getActivity().runOnUiThread(new Runnable() {
                @Override
                public void run() {
                    viewMobileNetworkCode.setText(cellInfo.getMnc() + "");
                    // Invalidate the view that changed
                    viewMobileNetworkCode.invalidate();
                }
            });
        }
    }

    /** Update the text field with the current value of MCC */
    private void updateMCCView() {
        // Check that the view has been initialized
        if (viewMobileCountryCode != null) {
            // Access the UI from the main thread
            getActivity().runOnUiThread(new Runnable() {
                @Override
                public void run() {
                    viewMobileCountryCode.setText(cellInfo.getMcc() + "");
                    // Invalidate the view that changed
                    viewMobileCountryCode.invalidate();
                }
            });
        }
    }

    /** Update the text field with the current value of CID */
    private void updateCIDView() {
        // Check that the view has been initialized
        if (viewCellId != null) {
            // Access the UI from the main thread
            getActivity().runOnUiThread(new Runnable() {
                @Override
                public void run() {
                    viewCellId.setText(cellInfo.getCid() + "");
                    // Invalidate the view that changed
                    viewCellId.invalidate();
                }
            });
        }
    }

    /** Update the Cell Info view */
    private void updateCellInfo() {
        // Check that the view has been initialized
        if (viewCellInfo != null)
            getActivity().runOnUiThread(new Runnable() {
                @Override
                public void run() {
                    viewCellInfo.updateCellInfo(cellInfo);
                }
            });
    }

}