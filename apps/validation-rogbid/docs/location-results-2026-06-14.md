# Rogbid Location Probe Results - 2026-06-14

## Controls

- Device: Rogbid Model X, serial `46734915123233`.
- Box: the Linux build host, wired ADB.
- App: `org.solpbc.rogbidhello`, debug APK built from this spike tree with
  `LocationProbeActivity`.
- Permissions granted: `ACCESS_FINE_LOCATION`, `ACCESS_COARSE_LOCATION`.
- Connectivity during probe: Wi-Fi connected and validated; telephony present
  but out of service / emergency-only; no activated cellular data path.

## Shell Hardware Surface

Android advertises both physical and network location capability:

- `feature:android.hardware.location`
- `feature:android.hardware.location.gps`
- `feature:android.hardware.location.network`
- `feature:android.hardware.telephony`
- `feature:android.hardware.telephony.gsm`
- `feature:android.hardware.wifi`

GNSS/vendor services were running:

- `init.svc.gpsd=running`
- `init.svc.vendor.gnss_service_sprd=running`
- `ro.vendor.gnsschip=ge2`
- `ro.vendor.wcn.gpschip=ge2`

Google location overlays are installed:

- `network: com.google.android.gms`
- `fused: com.google.android.gms`
- package `com.google.android.gms`
- package `com.android.location.fused`

Telephony exists, but it was not carrying service:

- `mVoiceRegState=OUT_OF_SERVICE`
- `mDataRegState=OUT_OF_SERVICE`
- `mIsEmergencyOnly=true`
- `mUserMobileDataState=false`

Wi-Fi was connected and validated:

- `type: WIFI`, `state: CONNECTED/CONNECTED`
- address `192.168.4.69/22`
- capabilities include `INTERNET` and `VALIDATED`

## App-Level Provider Probe

With the watch's original provider setting:

```text
location_providers_allowed=gps
all_providers=[passive, gps, network]
enabled_providers=[passive, gps]
provider_gps_enabled=true
provider_network_enabled=false
request_providers=[gps]
requested_gps=true
```

The first run timed out after 45 seconds with no GPS callback and no cached
last-known location. A later run received an active GPS update:

```text
update_gps=provider:gps,lat:39.896491033333334,lon:-104.80043495,accuracy:14.967357,time:1162177119000,elapsed_nanos:292909041793185
done_gps=true
```

That is the load-bearing result: a normal third-party app with runtime location
permission can get a GPS provider fix from the Rogbid watch. This is not
network-only and does not require an activated 4G/cellular link.

The `network` provider remained disabled at the app layer. Attempts to enable it
temporarily through Android's legacy secure settings left app-visible providers
unchanged:

```text
original_providers=gps
enabled_for_probe=gps
provider_network_enabled=false
```

Setting `location_mode=3` also left the app-visible active provider set as
GPS-only:

```text
probe_providers=gps
probe_mode=3
enabled_providers=[passive, gps]
provider_network_enabled=false
```

After the GPS fix, passive/fused carried a cached location:

```text
last_passive=provider:fused,lat:39.896491,lon:-104.8004349,accuracy:14.967,time:1781492315254,elapsed_nanos:292909041793185
```

The probe restored the original secure settings afterward:

```text
restored_providers=gps
restored_mode=null
```

## Readout

- **Built-in GPS: yes.** The watch advertises GPS hardware and returned an
  app-level GPS callback.
- **Cellular activation required for GPS: no.** The fix occurred while telephony
  was out of service and mobile data was disabled.
- **Network location: present but not proven usable by our app.** Android
  advertises `android.hardware.location.network` and GMS owns `network` /
  `fused` overlays, but this firmware state kept `network` disabled for a normal
  third-party app despite Wi-Fi being connected. Treat network location as
  unproven until a UI-level setting or vendor setting is found that enables it.
- **Practical path for a watch observer:** location is possible via GPS today.
  Expect battery cost from active GPS, and treat Wi-Fi/cell network location as
  a possible optimization only after a separate enablement probe.
