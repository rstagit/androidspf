دانلود از قسمت release:

https://github.com/rstagit/androidspf/releases/

# RSTA Spoof 🛡️
**Advanced SNI Spoofing & DPI Bypass for Android.**


RSTA Spoof is a high-performance local proxy for Android designed to bypass Deep Packet Inspection (DPI) using advanced techniques like TTL tricks and SNI fragmentation.

### Features ✨
*   **v4.0.0 Engine:** Powered by a high-speed Go-based core.
*   **DPI Bypass:** Advanced TTL Trick + SNI Split Fragmentation.
*   **Live Monitoring:** Real-time packet inspection and live logs terminal.
*   **Auto-Save:** Automatically remembers your last SNI, IP, and Port.
*   **Cyber UI:** Modern, sleek, and user-friendly dark interface.

### Support 📢
For help, updates, and discussions, join our official community:
[**@rstasnispoof on Telegram**](https://t.me/rstasnispoof)
[گروه تلگرام @rstasnispoof](https://t.me/rstasnispoof)

---

### Usage 📱
Using RSTA Spoof is very simple:

1.  **Configure:** Enter your target **FAKE SNI** (e.g., `www.hcaptcha.com`) and the **SERVER IP**.
2.  **Connect:** Tap the large **Power Button** at the top.
3.  **Proxy Setup:** Once connected, configure your browser or application to use the proxy:
    *   **Address:** `127.0.0.1`
    *   **Port:** The port you specified in the app (default: `40443`).
4.  **Watch Logs:** Check the **LIVE LOGS** section at the bottom to ensure the SNI spoof is active.

---

### FAQ 💬

**How do I use it?**
Fill in the SNI and IP fields, press connect, and set your device or app's proxy settings to the local IP and port shown in the app.

**Does it require Root?**
No! The fragmentation and TTL trick methods used in v4.0.0 work perfectly on non-rooted devices.

**How do I update settings?**
The app automatically saves your inputs. Just type the new SNI or IP and it will be remembered for the next time you open the app.

**Is it safe?**
Yes, the project is fully open-source and uses standard networking protocols to redirect traffic locally.

---

### License 📜
This project is licensed under the **GPL (GNU General Public License)**. You are free to share and modify it under the terms of this license.

### Disclaimer ⚖️
This tool is intended for educational and testing purposes. The developers are not responsible for any misuse of this software. All trademarks belong to their respective owners.

---

### Build 🛠️
To build the project yourself:
1. Clone the repository.
2. Ensure you have **Go** and **Android NDK** installed.
3. Run `gobridge/build.sh` to compile the native core.
4. Open the project in **Android Studio** and build the APK.

**Don't forget to ⭐ this repo if you like the project!**
