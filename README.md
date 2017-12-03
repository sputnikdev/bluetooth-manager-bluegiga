[![Maven Central](https://img.shields.io/maven-central/v/org.sputnikdev/bluetooth-manager-bluegiga.svg)](https://mvnrepository.com/artifact/org.sputnikdev/bluetooth-manager-bluegiga)
[![Build Status](https://travis-ci.org/sputnikdev/bluetooth-manager-bluegiga.svg?branch=master)](https://travis-ci.org/sputnikdev/bluetooth-manager-bluegiga)
[![Coverage Status](https://coveralls.io/repos/github/sputnikdev/bluetooth-manager-bluegiga/badge.svg?branch=master)](https://coveralls.io/github/sputnikdev/bluetooth-manager-bluegiga?branch=master)
[![Codacy Badge](https://api.codacy.com/project/badge/Grade/ac4525af60a54879a5084d2fb441b170)](https://www.codacy.com/app/vkolotov/bluetooth-manager-bluegiga?utm_source=github.com&amp;utm_medium=referral&amp;utm_content=sputnikdev/bluetooth-manager-bluegiga&amp;utm_campaign=Badge_Grade)
[![Join the chat at https://gitter.im/sputnikdev/bluetooth-manager-bluegiga](https://badges.gitter.im/sputnikdev/bluetooth-manager-bluegiga.svg)](https://gitter.im/sputnikdev/bluetooth-manager-bluegiga?utm_source=badge&utm_medium=badge&utm_campaign=pr-badge&utm_content=badge)
# bluetooth-manager-bluegiga
A transport implementation for Bluetooth Manager based on Bluegiga API to work with BLE112 dongles (or similar)


## Troubleshooting

* Adapters are not getting discovered.
  * Make sure your user has got sufficient permissions to access adapters (serial ports) in your OS (e.g. `sudo adduser <username> dialout`)

---
## Contribution

To build the project you will need to install the Bluegiga library into your maven repository. Run this in the root of the project:
```sh
sh .travis/install-dependencies.sh
```

Then run maven build:
```sh
mvn clean install
```