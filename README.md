# NexusTPA

**NexusTPA** adalah plugin *cross-server teleport request* (`/tpa`, `/tpahere`, `/tpaccept`, dll.) berbasis multi-modul yang dirancang khusus untuk jaringan Minecraft Server (Velocity Proxy + Paper Backend). 

Plugin ini dibangun untuk menangani pembagian **Server Groups** (Kelompok Server) secara dinamis melalui konfigurasi, sehingga dapat membatasi interaksi teleportasi antar server berdasarkan kelompok yang ditentukan (contoh: membiarkan teleportasi antara server survival, sementara mengisolasi server hub dan RPG).

---

## 🚀 Fitur Utama

- **Teleportasi Lintas Server (Cross-Server TPA):** Mengizinkan pemain untuk berkirim permintaan teleportasi (`/tpa` dan `/tpahere`) antar server dalam kelompok yang sama secara transparan.
- **Isolasi Server Group (Config-Driven):** Pembagian kelompok server didefinisikan secara fleksibel dalam konfigurasi proxy, tidak di-*hardcode* di dalam kode program.
- **Tab-Complete Terisolasi:** Fitur auto-complete nama pemain hanya akan menyarankan nama pemain lain yang berada di dalam kelompok server yang sama, menjaga privasi antar kelompok server.
- **Dua Fase Teleportasi Aman:** Menghindari teleportasi langsung antar JVM dengan menyimpan data *pending teleport* sementara dan melakukan pemindahan server secara aman melalui proxy.
- **Penyimpanan Database MySQL:** Digunakan khusus untuk menyimpan data *cooldown* pemain dan status toggle `/tptoggle` agar tetap persisten.
- **Degradasi Sistem yang Aman (Safe Degradation):** Jika plugin proxy tidak berjalan atau koneksi terputus, backend secara otomatis akan menurun ke mode *single-server* (terdegradasi tetapi tidak rusak/crash).
- **Dukungan MiniMessage & Bahasa Indonesia:** Semua pesan dalam game menggunakan format modern MiniMessage (Adventure API) dan dilokalisasikan ke dalam Bahasa Indonesia secara bawaan.

---

## 🗺️ Arsitektur Sistem

Teleportasi antar server tidak boleh langsung menyentuh database secara berkala (*polling*), melainkan menggunakan **Plugin Messaging Channels** demi menjaga performa server:

```
                         ┌─────────────────────────┐
                         │   Velocity Proxy         │
                         │  nexustpa-proxy plugin   │
                         │                          │
                         │ - Daftar Server Group    │
                         │ - Index Live Pemain      │
                         │ - Push Roster Pemain     │
                         │   ke Backend             │
                         └───────────┬──────────────┘
                     plugin messaging channel
                     (nexustpa:sync, nexustpa:connect)
                 ┌───────────────────┼───────────────────┐
                 ▼                   ▼                   ▼
        ┌────────────────┐ ┌────────────────┐  ┌────────────────┐
        │ Terra-Spawn     │ │ Terra-Realms    │  │ Hub / RPG-Realm│
        │ nexustpa-backend│ │ nexustpa-backend│  │ nexustpa-backend│
        │                 │ │                 │  │                │
        │ - Roster Terra  │◄┤ - Roster Terra  │  │ - Roster Hub   │
        │ - /tpa Commands │ │ - /tpa Commands │  │ - /tpa Commands│
        │ - MySQL Data    │ │ - MySQL Data    │  │ - MySQL Data   │
        └─────────────────┘ └─────────────────┘  └────────────────┘
```

---

## 🛠️ Perintah (Commands) & Izin (Permissions)

| Perintah | Alias | Deskripsi | Node Izin (Permission) | Default |
|---|---|---|---|---|
| `/tpa <player>` | `/tpask` | Meminta teleportasi **ke** pemain tujuan. | `nexustpa.use` | True |
| `/tpahere <player>` | | Meminta pemain tujuan teleportasi **ke lokasi Anda**. | `nexustpa.use` | True |
| `/tpaccept [player]` | `/tpyes` | Menerima permintaan teleportasi masuk yang tertunda. | `nexustpa.use` | True |
| `/tpdeny [player]` | `/tpno` | Menolak permintaan teleportasi masuk yang tertunda. | `nexustpa.use` | True |
| `/tpcancel` | | Membatalkan permintaan teleportasi keluar yang Anda kirim. | `nexustpa.use` | True |
| `/tptoggle` | | Mengaktifkan/menonaktifkan penerimaan TPA untuk diri sendiri. | `nexustpa.toggle` | True |
| `/tpareload` | | Memuat ulang konfigurasi dan pesan (Proxy & Backend). | `nexustpa.admin.reload` | OP |

> [!NOTE]
> Pemain dengan permission `nexustpa.cooldown.bypass` (bawaan: OP) akan mengabaikan waktu tunggu (*cooldown*) teleportasi.

---

## ⚙️ Langkah Setup & Instalasi

### Prasyarat (Requirements)
1. Java Development Kit (JDK) 21 atau lebih baru.
2. Apache Maven 3.8+ untuk melakukan kompilasi.
3. Server Minecraft dengan Velocity Proxy dan game server berbasis Paper (1.21.1+).
4. Database MySQL (versi 8.0+ recommended) untuk menyimpan data user.

### 1. Kompilasi Source Code
Jalankan perintah Maven berikut di direktori utama proyek untuk mem-build file `.jar`:
```bash
mvn clean package
```
Setelah kompilasi selesai, Anda akan mendapatkan file berikut:
- **Proxy Jar:** `nexustpa-proxy/target/nexustpa-proxy-1.0.0-SNAPSHOT.jar`
- **Backend Jar:** `nexustpa-backend/target/nexustpa-backend-1.0.0-SNAPSHOT.jar`

### 2. Setup pada Velocity Proxy
1. Masukkan file JAR proxy ke dalam folder `plugins/` pada directory Velocity Proxy Anda.
2. Jalankan atau restart Proxy untuk membuat folder konfigurasi.
3. Edit file `plugins/nexustpa/config.yml` untuk menentukan pembagian server groups:
   ```yaml
   groups:
     terra:
       servers:
         - "Terra-Spawn"
         - "Terra-Realms"
     hub:
       servers:
         - "Hub"
     rpg:
       servers:
         - "RPG-Realm"
   ```
   *Pastikan nama server di atas sama persis dengan yang terdaftar di dalam `velocity.toml`.*
4. Restart proxy atau jalankan reload untuk menerapkan perubahan.

### 3. Setup pada Paper Backend (Setiap Game Server)
1. Masukkan file JAR backend ke dalam folder `plugins/` pada setiap server Paper.
2. Jalankan server Paper sekali untuk memicu pembuatan folder data dan file konfigurasi.
3. Konfigurasikan file `plugins/NexusTPA/config.yml` pada masing-masing backend server:
   - **`server-name`**: Wajib diisi dan harus **sama persis** dengan nama server yang terdaftar di `velocity.toml` (misalnya `"Terra-Spawn"`).
   - **`storage`**: Masukkan informasi koneksi ke database MySQL Anda.
   ```yaml
   server-name: "Terra-Spawn"
   request-timeout: 60
   cooldown-seconds: 5
   pending-teleport-ttl: 15

   storage:
     mysql:
       host: localhost
       port: 3306
       database: nexustpa
       username: root
       password: ""
       pool-size: 10
   ```
4. Anda dapat menyesuaikan pesan dalam game pada file `plugins/NexusTPA/messages.yml` jika diperlukan (berwarna dan mendukung MiniMessage).
5. Restart backend server Anda.

### 4. Skema Database (MySQL)
Proyek ini secara otomatis membuat tabel-tabel berikut saat pertama kali backend terhubung ke database. Jika Anda ingin menyiapkannya secara manual, berikut adalah struktur skemanya:
```sql
CREATE TABLE IF NOT EXISTS nexustpa_cooldowns (
    player_uuid CHAR(36) PRIMARY KEY,
    last_teleport_at BIGINT NOT NULL
);

CREATE TABLE IF NOT EXISTS nexustpa_toggle_state (
    player_uuid CHAR(36) PRIMARY KEY,
    accepting_requests BOOLEAN NOT NULL DEFAULT TRUE
);
```

---

## 🪵 Struktur Modul Proyek
- **`nexustpa-common`**: Berisi shared model (seperti representasi `TpaRequest` dan status `RequestState`), utilitas serialisasi paket pesan, dan pesan key.
- **`nexustpa-proxy`**: Plugin khusus Velocity yang mengatur database kelompok server secara dinamis dan melacak status online serta server dari setiap pemain.
- **`nexustpa-backend`**: Plugin Paper yang berisi seluruh logika perintah game, validasi kelompok server, tab-completion cache, serta integrasi database MySQL.
