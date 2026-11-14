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

## Arayüz (kullanıcının yeniden tasarım isteği)
- **Dikey ekran:** Yatay zorunluluğu kaldırıldı; oyun her iki yönde de çalışır. Yatay kilit altyapısı kodda duruyor ama hiçbir oyun kullanmıyor.
- **Masa:** Ekranın merkezinde, lambayla aydınlatılmış oval metal ve keçe bir masa var.
  - Tüfek masanın ortasındadır ve nişan alınan oyuncuya (yoksa sırası gelene) döner.
  - Dolu atışta namlu ucunda bir parlama olur.
- **Oyuncular:**
  - Rakipler masanın çevresinde oturur: 2 oyuncuda karşıda, 3 oyuncuda üst köşelerde, 4 oyuncuda solda, karşıda ve sağda.
  - Her oyuncunun avatarı, adı, can lambaları ve durumu (kelepçeli, testere, kazandığı çantalar) görünür.
  - Sırası gelen oyuncu ışıkla, nişan alınan kırmızıyla belirginleşir.
  - Elenen oyuncu soluk ve ✕ ile masada kalır.
- **Eşyalar:**
  - Oyuncunun eşyaları ekranın altında, keçe şerit üzerinde çizilmiş nesnelerdir.
  - Seçilen eşya kalkar ve ışıklanır; "KULLAN" ile kullanılır. Hedef gereken eşyalarda masadan bir rakip seçilir.
  - Rakiplerin eşyaları, önlerinde kapalı kasalar olarak durur; yalnızca sayısı belli olur.
- **Mermiler (gizlilik düzeltmesi):**
  - Her yeni şarjörde "Şarjör yüklendi: X dolu, Y boş" duyurusu bir kez yaklaşık 3,5 saniye görünür ve kaybolur.
  - Sonrasında masada yalnızca "Kalan mermi" sayısı kalır. Dolu/boş toplamları, mermi simgeleri, sıra ve tür gösterilmez.
  - Olay satırında da şarjör sayıları yazmaz, yalnızca "Yeni şarjör yüklendi" görünür.
  - Atış sonucu (dolu ya da boş, verilen hasar) ve Gazoz ile çıkarılan merminin türü olay olarak görünür, çünkü herkes masada olanı görür.
  - **Olasılık hesabı yapılmaz.**
- **Büyüteç ve Telsiz sonucu:**
  - Yalnızca kullanan oyuncunun ekranında görünür: 4 saniyelik "Yalnızca sen görüyorsun" bildirimi ve kendi avatarının altında küçük bir hatırlatma ("🔍 3. DOLU").
  - Bu bilgi oyuncuya özel veriden (`me.known`) gelir ve ortak durumda hiç yer almaz.
  - Sunucu mermilerin gerçek durumunu saklamaya devam eder; kurallar değişmedi.
- **Animasyonlar:** Azaltılmış hareket açıkken tüfeğin dönüşü, lamba nabzı, sıra ışığı, eşya kalkışı, atış parlaması ve para çantası animasyonu kapalıdır.

## Bilinen sınırlamalar
- **Cihaz testi yapılmadı.** Ekran Robolectric ile dikey düzende, 3 ve 4 oyunculu masalarla test edildi.
- **Çizimler sınırlı:** Masa ve nesneler vektör çizimdir (Canvas); hazır 3B görsel kullanılmadı.
- **Cihazda denenmedi:** Dokunma hissi ve animasyon akıcılığı gerçek cihazda değerlendirilmeli.
