# Buckshot — uygulama notları (0.9.0)

Kaynak: kullanıcının "LOOPA — BUCKSHOT MULTIPLAYER INTEGRATION" dokümanı ve aşağıdaki kararları.
Görseller, item adları ve arayüz LOOPA'ya özgüdür; kaynak oyunun görsel öğeleri kullanılmadı.

**Dosyalar**
- Sunucu:
  - `supabase/migrations/20261121000001_buckshot.sql`
  - geri alma: `supabase/rollback/20261121000001_buckshot_down.sql`
- İstemci: `feature-game/.../modes/BuckshotScreen.kt`
- Testler:
  - `supabase/tests/buckshot_test.sql`
  - `core-data/.../BuckshotE2ETest.kt`
  - `app/.../BuckshotScreenTest.kt`

## Kullanıcının verdiği kararlar
| Konu | Karar |
|---|---|
| Kumanda (Remote) | Yalnızca sıra yönünü tersine çevirir. 2 oyunculu maçta etkisiz olduğu için dağıtılmaz. |
| Raund sonu | Tek kişi kalınca raund hemen biter, o kazanır. Şarjör bittiğinde 2+ kişi hayattaysa (biri elenmiş olsa da) aynı raund yeni şarjör ve +2 eşya ile sürer. |
| Süre dolunca / bağlantı kopunca | Sunucu hayattaki rastgele bir rakibe ateş eder; eşya kullanmaz. Hamle süresi 30 sn; oda sahibi 20, 30 ya da 45 seçebilir. |
| Şarjör | 8 mermi; dolu sayısı 1–7 arası rastgele. Yüklenen dolu/boş sayısı herkese açık, sırası gizli. |

## Dokümanda açık kalan ve uygulamada karar verilen noktalar (onay bekliyor)
- **3 oyuncu 1-1-1 durumu:** Doküman "eşitlik mümkün değil" diyor, ama 3–4 oyuncuda üç farklı kişi birer raund kazanabilir. Dördüncü raund olmadığı için eşitlikte **3. raundun galibi** şampiyon olur (+1 puan). Puanlama: raund başına 10 puan.
- **Testere:** "sonraki gerçek mermi" ifadesi şöyle uygulandı: Testere, kullananın ateşlediği ilk dolu mermiye kadar etkin kalır. Boş mermiler etkiyi bitirmez. Etkinken ikinci Testere kullanılamaz.
- **Gazoz (Beer):** Çıkarılan merminin türü herkese görünür. Kalan dolu/boş sayıları zaten açık olduğu için bu bilgi saklanamazdı. Mermiyi çıkaran oyuncunun sırası devam eder.
- **Çevirici:** Kullanıldığı herkese görünür, sonucu görünmez. Arayüz "tahmin kesin değil" uyarısı gösterir. O mermiyi daha önce Büyüteç ya da Telsiz ile öğrenmiş olanların bilgisi de tersine çevrilir.
- **Telsiz (Burner Phone):** Sıradaki mermi dışındaki kalan mermilerden birini gösterir. Yalnızca tek mermi kaldıysa onu gösterir.
- **Şüpheli İlaç:** +2 can, raundun azami canını aşmaz. −1 can, oyuncuyu eleyebilir.
- **Kelepçe:** Yalnızca hayattaki bir rakibe takılır; zaten kelepçeli olana takılamaz. 2 oyunculu maçta kelepçeyi takan oyuncu üst üste oynar.
- **Eşya adları LOOPA'ya uyarlandı:**
  - Bira → Gazoz
  - Sigara → Yara Bandı
  - Burner Phone → Telsiz
  - Inverter → Çevirici
  - Expired Medicine → Şüpheli İlaç
  - Remote → Kumanda
  - İşlevleri değişmedi.
- **Raund sonu dağıtım:** Şarjör yenilenince +2 eşya yalnızca hayattaki oyunculara verilir.
- **Rakip eşyaları:** Rakiplerin eşyaları gizlidir; herkes yalnızca kaç eşyaları olduğunu görür. Kullanılan eşyalar oyun kaydında görünür.

## Ödüller (merkezî ayar: `economy_config['buckshot']`)
- **Para çantası:**
  - Varsayılan 100 coin; raundu kazanan alır.
  - Mevcut coin defterine yazılır, raund başına bir kez. Anahtar: `buckshot_bag:<maç>:<raund>`.
  - Günde en fazla 15 çanta (`bag_daily_cap`).
- **Maç galibiyeti:** Mevcut maç ödül sistemiyle verilir (sınıf `ffa`). Kayıp ve bahis yoktur; kaybedenin bakiyesi hiç azalmaz.
- **Diğer ayarlar:** eşya kapasitesi 8, raund başına 4 eşya, şarjör yenilenince 2 eşya. Hepsi ayardan değiştirilebilir.

## Güvenlik
- Şarjörün sırası ve kimin neyi öğrendiği yalnızca sunucuda tutulur (`mode_secrets`).
- İstemciye giden veriler: kalan mermi, yüklenen ve görülen sayılar, kendi bilgisi.
- **Tekrarlanan istekler:** Her hamle bir `nonce` taşır. Aynı nonce tekrar gelirse `DUPLICATE_ACTION` döner. Böylece aynı istek ikinci kez ateş edemez, can veremez, eşya harcayamaz.
- **Reddedilen hamleler:**
  - sırası olmayan ya da katılımcı olmayan oyuncunun hamlesi,
  - ölü hedefe ateş,
  - sahip olunmayan eşya,
  - bilinmeyen eylem.
  - Kullanılamayan eşya (dolu canla Yara Bandı vb.) harcanmaz.

## Bilinen sınırlamalar
- **Cihaz testi yapılmadı.** Ekran Robolectric ile yatay ve dikey düzende test edildi.
- **Yatay ekran kilidi:** Buckshot açıkken ekran yatay konuma kilitlenir; çıkışta eski ayara döner. Bu davranış da yalnızca testte doğrulandı.
- **Animasyonlar basit:** dolu atışta kırmızı parlama ve para çantası nabzı. Azaltılmış harekette ikisi de kapalı.
