# NexusTPA

NexusTPA adalah sistem teleportasi lintas server (TPA) yang dirancang untuk jaringan server Minecraft berbasis Velocity Proxy dan Paper. Plugin ini mengimplementasikan logika isolasi kelompok server (*server groups*) untuk membatasi interaksi teleportasi sesuai dengan konfigurasi yang ditentukan.

## Fitur

* **Teleportasi Lintas Server**: Pemain dapat mengirimkan permintaan teleportasi (`/tpa` dan `/tpahere`) ke pemain lain di server berbeda yang berada dalam satu kelompok server.
* **Isolasi Kelompok Server**: Logika pengelompokan server dikelola secara dinamis melalui konfigurasi proxy tanpa memerlukan modifikasi kode sumber.
* **Pembatasan Tab-Complete**: Pengisian otomatis nama pemain tujuan dibatasi hanya untuk pemain yang berada di kelompok server yang sama.
* **Teleportasi Dua Fase**: Mekanisme teleportasi lintas server menggunakan sistem *pending teleport* berbasis waktu (TTL) dan diproses melalui plugin messaging.
* **Penyimpanan Status Persisten**: Data waktu tunggu (*cooldown*) dan preferensi penerimaan teleportasi (`/tptoggle`) disimpan di database MySQL.
* **Penanganan Kegagalan**: Apabila plugin proxy tidak aktif, plugin backend akan beralih ke mode server tunggal secara otomatis untuk menjaga kelangsungan sistem.

## Arsitektur

Interaksi antar server dilakukan menggunakan *Plugin Messaging Channels* (`nexustpa:sync` dan `nexustpa:connect`) untuk menghindari kueri database yang terus-menerus.

```
[Velocity Proxy (nexustpa-proxy)]
       │ (Melacak status & mendistribusikan roster pemain)
       ├───> [Server Paper A (nexustpa-backend)]
       └───> [Server Paper B (nexustpa-backend)]
```

## Perintah dan Izin

| Perintah | Alias | Deskripsi | Izin (Permission) | Bawaan |
|---|---|---|---|---|
| `/tpa <pemain>` | `/tpask` | Mengirim permintaan teleportasi ke pemain tujuan. | `nexustpa.use` | true |
| `/tpahere <pemain>` | | Meminta pemain tujuan teleportasi ke lokasi Anda. | `nexustpa.use` | true |
| `/tpaccept [pemain]` | `/tpyes` | Menerima permintaan teleportasi yang masuk. | `nexustpa.use` | true |
| `/tpdeny [pemain]` | `/tpno` | Menolak permintaan teleportasi yang masuk. | `nexustpa.use` | true |
| `/tpcancel` | | Membatalkan permintaan teleportasi keluar. | `nexustpa.use` | true |
| `/tptoggle` | | Mengatur status penerimaan permintaan teleportasi. | `nexustpa.toggle` | true |
| `/tpareload` | | Memuat ulang file konfigurasi plugin. | `nexustpa.admin.reload` | op |

*Catatan: Pemain dengan izin `nexustpa.cooldown.bypass` (bawaan: op) tidak dikenakan waktu tunggu teleportasi.*

## Konfigurasi

### Proxy (`nexustpa-proxy/config.yml`)
Kelompok server didefinisikan pada sisi proxy. Pemain hanya dapat berinteraksi dengan pemain lain dalam satu kelompok.
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

### Backend (`nexustpa-backend/config.yml`)
Setiap game server harus memiliki nama yang sesuai dengan konfigurasi pada `velocity.toml`.
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

## Panduan Pemasangan

### Persyaratan Sistem
* Java Development Kit (JDK) 21 atau versi terbaru.
* Apache Maven 3.8 atau versi terbaru.
* Velocity Proxy dan server Paper (1.21.1 atau versi terbaru).
* Server MySQL.

### Langkah-Langkah Setup

1. **Kompilasi Kode Sumber**:
   Jalankan perintah berikut pada direktori utama proyek untuk membuat file pustaka (JAR):
   ```bash
   mvn clean package
   ```
   File hasil kompilasi akan berada pada lokasi berikut:
   * Proxy: `nexustpa-proxy/target/nexustpa-proxy-1.0.0-SNAPSHOT.jar`
   * Backend: `nexustpa-backend/target/nexustpa-backend-1.0.0-SNAPSHOT.jar`

2. **Konfigurasi Proxy**:
   * Salin file JAR proxy ke direktori `plugins` server Velocity.
   * Jalankan proxy untuk menghasilkan struktur folder konfigurasi awal.
   * Sesuaikan daftar server pada `plugins/nexustpa/config.yml`.
   * Restart proxy atau muat ulang plugin.

3. **Konfigurasi Backend**:
   * Salin file JAR backend ke direktori `plugins` pada masing-masing server Paper.
   * Jalankan server untuk menghasilkan folder konfigurasi awal.
   * Edit berkas `plugins/NexusTPA/config.yml`. Sesuaikan parameter `server-name` agar sesuai dengan nama server di `velocity.toml` dan lengkapi kredensial MySQL.
   * Restart server Paper.

4. **Struktur Database**:
   Plugin akan membuat tabel berikut secara otomatis saat pertama kali terhubung ke MySQL:
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
