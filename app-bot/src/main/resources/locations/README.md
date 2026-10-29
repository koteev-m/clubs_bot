# Offline location snapshot

Contains a derivative of **GeoNames cities15000 and admin1CodesASCII**, downloaded
2026-10-29 from <https://download.geonames.org/export/dump/>. Attribution: GeoNames,
<https://www.geonames.org/>. Licensed under **CC BY 4.0**:
<https://creativecommons.org/licenses/by/4.0/>.

The source includes cities above 15,000 inhabitants and capitals; it is not a complete
address or settlement database. Data is supplied without accuracy/completeness warranty.
We retain ID, country, city, region, IANA zone and name aliases; discard population,
coordinates and other fields; sort by stable GeoNames ID and gzip deterministically.
`manifest.json` records input/output SHA-256 and row count. Reproduce with
`scripts/build-onboarding-locations.py INPUT_DIRECTORY OUTPUT_DIRECTORY`.

The server searches this packaged snapshot, never a live geocoder. Country labels
come from JDK Russian locale display names. A selected ID determines timezone;
unknown IDs, mismatched countries and client-supplied timezone overrides are rejected.
Cities whose zone is unsupported by the running JVM are not offered. Unsupported or
ambiguous places require refinement/region selection, never a device/default timezone.
The source primary city/region names may be in Latin script; aliases support local-script
search. No automatic dataset refresh, network request, paid service or address transfer.
