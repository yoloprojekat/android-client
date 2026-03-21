<div align="center">

# 📱 Pametno Vozilo Android
### *Moderni Kontrolni Terminal za Autonomne Sisteme*

[![Kotlin](https://img.shields.io/badge/Kotlin-2.0%2B-38bdf8?style=for-the-badge&logo=kotlin&logoColor=white)](https://kotlinlang.org/)
[![Jetpack Compose](https://img.shields.io/badge/UI-Material_3-075985?style=for-the-badge&logo=jetpackcompose&logoColor=white)](https://developer.android.com/jetpack/compose)
[![Edge AI](https://img.shields.io/badge/AI-YOLOv26_on_RPi5-FF8C00?style=for-the-badge&logo=raspberrypi&logoColor=white)](https://www.raspberrypi.com/)
[![OCR](https://img.shields.io/badge/OCR-Text_Commands-2ECC71?style=for-the-badge&logo=googlecloud&logoColor=white)](https://developers.google.com/ml-kit)
[![Android 15](https://img.shields.io/badge/Platform-Android_15%2B-3DDC84?style=for-the-badge&logo=android&logoColor=white)](https://developer.android.com/)

---

<p align="center">
  <b>Pametno Vozilo Android</b> predstavlja visokooptimizovani klijentski interfejs. 
  <br>Sva AI obrada, iscrtavanje detekcionih frejmova se vrše na <b>Edge</b> nivou (RPi 5), dok aplikacija služi za telemetriju, OCR analizu, vizuelni nadzor i manuelno upravljanje.
</p>

</div>

## 🚀 Ključni Moduli

### 🧠 Server-Side Vision (AI & OCR)
* **Pre-Rendered Stream:** Raspberry Pi obrađuje YOLOv8 detekciju i iscrtava "Bounding Boxes" direktno na frejmove pre slanja. Android aplikacija samo prikazuje gotov video feed, čime se postiže 0% CPU opterećenja za AI na mobilnom uređaju.
* **OCR Command System:** Sistem na vozilu prepoznaje tekstualne komande iz okruženja (npr. saobraćajne znake ili ispisana uputstva) i automatski prilagođava kretanje, dok se status prepoznate komande ispisuje u realnom vremenu na Android terminalu.
* **Zero-Latency Display:** Optimizovano učitavanje slika visoke frekvencije koje obezbeđuje fluidan prikaz kretanja vozila.

### 🎮 Kontrolni Inženjering
* **Direct UDP Command Bridge:** Trenutni prenos korisničkih komandi sa džojstika na motore putem `DatagramSocket`-a (Port 1606).
* **Bi-Directional Feedback:** Aplikacija ne samo da šalje komande, već i vizuelno potvrđuje prijem OCR naredbi koje je vozilo samostalno donelo.
* **Custom Joystick UI:** Razvijen u Jetpack Compose-u, sa podrškom za precizno upravljanje brzinom i pravcem.
* **Arrows Control:** Kontrola pomocu strelica

### 📼 Optimizacija & Stabilnost
* **Thin Client Architecture:** Minimalna potrošnja baterije i resursa, omogućavajući dugotrajan rad na terenu.
* **16KB Page Alignment:** Potpuna podrška za Android 15 arhitekturu, osiguravajući kompatibilnost sa najnovijim standardima bezbednosti i performansi.

---

## 🛠 Tehnološki Stack

| Komponenta | Tehnologija | Uloga |
| :--- | :--- | :--- |
| **UI Framework** | Jetpack Compose (M3) | Fluidni "Glass" interfejs i animacije |
| **Networking** | HTTP Stream / UDP | Prijem obrađenog strima i slanje komandi |
| **AI Processing** | YOLOv26 (Server-Side) | Detekcija objekata na Raspberry Pi 5 |
| **OCR Engine** | OCR Intelligence | Prepoznavanje tekstualnih naredbi |
| **Asinhronost** | Kotlin Coroutines | Efikasno upravljanje mrežnim saobraćajem |

---

## 🔧 Mrežna Konfiguracija

Aplikacija komunicira sa vozilom kroz zatvorenu lokalnu mrežu, a preko hotspota, ili preko wifi-a ako su uredjaji povezani na istu mrezu


* **UDP Control:** `pametno-vozilo` (Low-latency kontrola)
* **HTTP Stream:** `http://pametno-vozilo/stream` (AI Rendered Feed)
* **Command:** Automatska OCR analiza sa vozila na klijent.

---

## 🎨 Vizuelni Identitet

Dizajn prati **Dark Future** temu sa fokusom na preglednost:
* 🔵 **Primary:** `#3498DB` — Glavna navigacija
* 🟢 **OCR Active:** `#2ECC71` — Potvrda prepoznate tekstualne komande
* 🟠 **AI Processing:** `#FF8C00` — Indikator aktivne YOLO detekcije
* 🌚 **Background:** `#121212` — Maksimalna ušteda energije na OLED ekranima.

---

<div align="center">

**Autor:** Danilo Stoletović • **Mentor:** Dejan Batanjac  
**ETŠ „Nikola Tesla“ Niš • 2026**

</div>
