# Krallıklar — uygulama notları (0.8.0)

Kaynak: kullanıcının gönderdiği Krallıklar spesifikasyonu. Sunucu motoru:
`supabase/migrations/20261120000008_kralliklar.sql`, testler: `supabase/tests/kralliklar_test.sql`.

## Spesifikasyondan birebir uygulananlar
- 4–20 oyuncu. Harita boyutu oyuncu sayısına göre 200…600 altıgen karo (tablo `_kr_map_size`).
- Rastgele, bağlantılı altıgen harita. Arazi kümeler halinde dağılır: ova, orman, maden, altın, tarım arazisi.
- Her krallığın bir adı, bir rengi ve tek karoluk bir başkenti vardır. Ad ve renk ilk turda değiştirilebilir; renkler benzersizdir.
- Başkentini kaybeden krallık elenir. Toprakları onu fethedene geçer.
- Savaş sisi: kendi karoların ve 1 karoluk çevre görünür. Diğer karoların yalnızca koordinatları bilinir.
- Kaynaklar:
  - Odun (orman) 5 tur, Demir (maden) 10 tur, Altın (altın) 20 tur ve daha az miktarda.
  - Tükenen karo sahibinde kalır ve yeniden dolmaz.
- Binalar:
  - Kışla: tur başına 5 asker üretir.
  - Pazar ve Çiftlik.
  - Bina maliyetleri kaynakla ödenir.
- Asker sayısı sınırsızdır. Yürüme süresi mesafeye bağlıdır. Savaşlar tur sonunda sunucuda çözülür: sayılar, savunan konumu, arazi bonusu ve sınırlı şans.
- Diplomasi:
  - Özel mektuplar (alıcı, konu, metin).
  - Resmî teklifler: saldırmazlık, ittifak, ticaret, ortak düşman, barış.
- İttifaklar en fazla 3 krallıktır. Müttefike saldıran ittifaktan çıkar ve bu herkese duyurulur.
- Ticaret iki krallık arasında atomik bir takastır. Taraflardan birinin kaynağı yetmezse başarısız olur ve hiçbir şey el değiştirmez.
- 60 saniyelik eşzamanlı planlama turları vardır. Herkes "hazır" derse tur erken biter.
- Zafer: ayakta kalan son krallık.

## Tur sonu işleme sırası
1. Binalar, verilme sırasıyla ödenir. Karşılanamayan bina başarısız olur ve bildirim gelir.
2. Kalkışlar: askerler karolarından ayrılır.
3. Varışlar ve savaşlar:
   - Önce aynı krallığın takviyeleri sayılır, sonra saldırılar en büyükten küçüğe çözülür.
   - Düşen başkent krallığı eler.
4. Üretim: başkent geliri, ömrü olan kaynak karoları, çiftlik, pazar ve kışla (kışlanın yiyecek bakım bedeli vardır).
5. Süresi dolan pakt ve tekliflerin temizlenmesi.
6. Zafer kontrolü.

## GEÇİCİ denge değerleri (spesifikasyon bölüm 16: test edilmeden kesinleştirilmedi)
Tüm değerler `economy_config['kralliklar_balance']` içinden admin tarafından değiştirilebilir. Değişiklik yeni başlayan oyunlara uygulanır. Varsayılanlar `_kr_default_balance()` içindedir:

| Değer | Geçici varsayılan |
|---|---|
| Tur süresi | 60 sn |
| Hız | 2 karo/tur (komşu ve 2 karo aynı turda varır) |
| Başlangıç askeri | 15 (başkentte) |
| Başlangıç kaynakları | odun 40, demir 20, altın 5, yiyecek 40 |
| Başkent geliri / tur | odun 3, altın 1, yiyecek 3, asker 2 |
| Orman | 8 odun/tur, 5 tur |
| Maden | 5 demir/tur, 10 tur |
| Altın | 2 altın/tur, 20 tur |
| Tarım arazisi | 6 yiyecek/tur, **15 tur (spesifikasyonda TBD)** |
| Savunma bonusu | orman +%20, maden +%30, altın +%10, başkent +%50 |
| Şans | ±%10 (her iki taraf) |
| Tarafsız garnizon | kaynak karolarında 2 |
| Kışla | 30 odun + 15 demir; 5 asker/tur; 3 yiyecek bakım |
| Pazar | 25 odun + 5 demir; +2 altın/tur |
| Çiftlik | 15 odun; +4 yiyecek/tur |
| Teklif geçerliliği | 2 tur |
| Saldırmazlık / ortak düşman | varsayılan 5 tur; barış varsayılanı 10 tur (2–30 seçilebilir) |

Savaş formülü:
- Saldırı gücü: saldıran × (1 ± şans).
- Savunma gücü: savunan × (1 + arazi + başkent) × (1 ± şans).
- Kazanan tarafta kalan asker: kazanan × (1 − kaybeden gücü / kazanan gücü). Saldıran kazanırsa en az 1 asker kalır.
- Kaybeden tarafın askerlerinin hepsi ölür.

## Yorum / karar gerektiren noktalar (kullanıcı onayı bekliyor)
- **Başlangıç toprağı:** başkent ve çevresindeki 6 karo. Çevredeki arazi herkes için aynıdır (2 orman, 2 tarım, 1 maden, 1 ova) ki başlangıç adil olsun.
- **Yürüme:** mesafe altıgen mesafesidir. Yoldaki karolar savaş üretmez; ordu yalnız hedefte çarpışır. Yoldaki ordular başka krallıklara görünmez.
- **Elenme:** elenen krallığın stok kaynakları da fethedene geçer. Diğer karolarındaki askerler dağılır, karolar boş olarak devredilir. Yoldaki orduları dağılır.
- **İttifak:** üyeler görüşü paylaşır. İttifak üyelikleri herkese açıktır.
- **Saldırmazlık, barış ve ortak düşman:** resmî kayıtlardır. Mekanik bir yasak getirmezler; bozulmaları herkese duyurulur.
- **Asker bakımı:** yalnızca kışla için yiyecek bedeli var. Ordu başına yiyecek tüketimi spesifikasyonda TBD olduğu için uygulanmadı.
- **Tur sınırı:** spesifikasyonda yok, güvenlik için eklendi. Varsayılan 100 tur; 60, 150 veya kapalı seçilebilir. Sınırda en güçlü krallık (karo ve asker) birinci olur.
- **Sıralama ve puan:** sonra elenen daha üstte yer alır. Kazanan, kendisinden kötü sıralanan krallık sayısı kadar puan alır.
- **Oyundan çıkan oyuncu:** krallığı pasif kalır (emir vermez) ve fethedilebilir.

## Ölçümler (yerel Postgres 17)
- 20 krallıklı oyunun başlangıcı, oda kurulumu dahil: yaklaşık 1,5 sn.
- Bir tur işleme: yaklaşık 0,06 sn.
- 20 oyuncunun özel görünümü: toplam yaklaşık 0,03 sn.

## Arayüz (0.9.0 — izometrik yeniden tasarım)

Yalnızca istemci değişti. Sunucu fonksiyonları, veri modeli, eylem yükleri (`orders`, `ready`,
`letter`, `offer`, `respond`, `cancel_offer`, `leave_alliance`, `rename`) ve kurallar aynı kaldı.
`kralliklar_test.sql` yeniden çalıştırıldı ve geçti.

Dosyalar: `feature-game/.../modes/KralliklarArt.kt` (çizim), `KralliklarMap.kt` (harita, kamera,
dokunma), `KralliklarScreen.kt` (ekran ve paneller).

- **Harita:** mevcut eksenel hex koordinatları, dikey 0.6 basıklıkla izometrik çiziliyor. Her bölge
  yüksekliği araziye göre değişen bir prizma. Çizim sırası arkadan öne; ekran dışı bölgeler
  çizilmiyor; bölge 9 px'ten küçükse ayrıntılar atlanıyor. Kaydırma, iki parmakla yakınlaştırma
  (0.8–5x), dokunarak seçim, `+ / − / başkent` düğmeleri var.
- **Arazi:** ova (çimen), tarım (tarla sıraları), orman (ağaçlar; tükenince kütükler), maden (karlı
  dağ zirveleri), altın tepesi (külçeler). Sahibin rengi bölgenin üstüne karışıyor; kendi
  bölgelerinin ek bir iç çizgisi var. Görülmeyen bölgeler koyu sis prizması.
- **Veri sınırı:** sunucu verisinde su veya nehir bölgesi yok. Kenardaki deniz yalnızca dekoratif
  arka plan; dağlar maden bölgeleri. Yeni arazi türü eklenmedi (kural değişikliği olurdu).
- **Birimler ve yapılar:** asker figürü ve sayı bayrağı, tarafsız garnizon kalkanı; kale (başkent),
  kışla, pazar, ambar. Planlanan ve yoldaki hareketler kavisli ok olarak çiziliyor: planlananlar
  kesikli ve hareketli, ETA etiketli. Son turdaki çatışmalar çapraz kılıç işaretiyle gösteriliyor.
- **Üst bölüm:** arma (krallık numarasına göre 6 amblem), krallık adı, tur, kimin hazır olduğu
  şeridi. Kaynak çubuğunda simge, değer ve önceki tura göre fark (+/−) var.
- **Alt panel:** seçili bölgenin türü, sahibi, askerleri, yapısı, kalan kaynak ömrü ve savunma
  bonusu. Yalnızca geçerli eylemler gösteriliyor: asker varsa "Asker gönder" (komşu seçimi,
  mesafe ve ETA, kaydırıcı); yapı yalnızca kendi boş bölgende, başkent dışında ve kuyruğa alınmış
  maliyetlerden sonra karşılanabiliyorsa.
- **Emirler:** açılır kartlar ve silme düğmesi, "Emirleri gönder" (kaydedilince "Gönderildi ✓"),
  "Hazırım". Kaydedilmemiş değişiklik renkli nokta ile işaretleniyor.
- **Diplomasi:** önce gelen teklifler, ardından ilişki rozetli krallık kartları (Müttefik, anlaşma
  ve bitiş turu, Nötr, Düştü). Ticaret teklifleri iki sütunda ("Sen alırsın / Sen verirsin").
  Mektup, krallık kartının altında açılan satır içi parşömen kartta yazılıyor; diyalog
  kullanılmamasının nedeni klavye açıkken alanın görünür kalması.
- **Raporlar:** özel olaylar ve genel kayıt, tura göre gruplu bir günlükte birleşiyor. Her satırın
  bir simgesi ve tonu var; "Haritada ›" ilgili bölgeyi seçip haritaya geçiyor.
- **Küçük ekran:** yükseklik 640 dp'nin altındaysa kompakt düzen kullanılıyor (küçük arma, tek
  satır kaynaklar, ipucu satırı gizli, alt panel en fazla 190 dp).
- **Hareket azaltma:** açıkken seçim nabzı ve rota animasyonu kapanıyor.
- **Ölçüm:** 600 bölgeli 20 krallık haritasında çizim süresi Robolectric/JVM üzerinde ölçüldü
  (`kralliklar_perf.txt`). Bu bir cihaz ölçümü değil; gerçek cihazda ölçülmedi.

### Pastel Party görsel geçişi

Yerleşim, bileşenler, test etiketleri ve gönderilen veriler aynı kaldı. Yalnızca görünüm değişti.

- **Palet:** paneller LOOPA temasına bağlandı. Açık temada lavanta tonlu açık zemin ve beyaz kartlar
  var; koyu temada LOOPA'nın mor gecesi kullanılıyor. Lacivert ve bordo kaldırıldı. Lavanta, mint,
  şeftali, pastel sarı ve açık mavi vurgu olarak kullanılıyor. Küçük metinler için bu renklerin
  koyu tonları seçildi; okunabilirlik böyle korunuyor.
- **Bileşenler:** kaynak kutuları yumuşak beyaz kartlar oldu ve her kaynağın kendi pastel çerçevesi
  ile simge dairesi var. Sekmeler hap biçiminde lavanta bir segment. Ana eylemler (Asker gönder,
  Emre ekle, Kabul et, Reddet, Kaydet, Mührü bas) uygulamanın ortak `LoopaButton` bileşenini
  kullanıyor. Emir çubuğunun düğmeleri aynı dilde, dar ekrana uyan bir sürüm. Kartlar 16–24 dp
  köşeli; sert çerçeveler yerini ince çizgiye ve hafif lavanta gölgeye bıraktı.
- **Diplomasi:** her teklif türünün kendi rengi var (saldırmazlık açık mavi, ittifak mint, ticaret
  pastel sarı, ortak düşman şeftali, barış lavanta). Ticaret iki renkli sütunda gösteriliyor ve
  aralarında ⇄ işareti var. İlişki rozetleri renkli hap biçiminde. Mektuplar krem kâğıt kartlarda.
- **Harita:** izometrik basıklık 0.56'ya indi ve bölge yükseklikleri arttı. Yan yüzler üç tonla
  (aydınlık, orta, gölge) ve toprak bandıyla çiziliyor; arka kenarda ışık çizgisi var. Arazi
  pastel ama ayırt edilebilir: ova yeşil, tarla sarı şeritli, orman mint ve iki katlı çam ağaçları
  ile yuvarlak ağaçlar, dağ lavanta-gri, üç zirveli ve maden girişli, altın tepesi şeftali-hardal
  tepeler ve külçelerle. Kale duvar, kuleler ve sahip renginde külahlı çatılardan oluşuyor; kışla
  çadır ve silahlık, pazar tenteli tezgâh, çiftlik ambar ve siloyla çiziliyor. Askerlerde miğfer,
  sorguç, mızrak ve kalkan var.
- **Sahiplik:** yüzey tonu hafifletildi (çamurlu renk oluşmasın diye). Sahiplik artık kalın
  kenar ve yan yüzlerdeki renk bandıyla okunuyor. Krallık renkleri sunucudan geldiği gibi
  kullanılıyor.
- **Deniz ve sis:** temaya uyuyor (açıkta açık mavi deniz ve beyaz bulutlu sis, koyuda mor gece).
- **Çok krallık:** 6'dan fazla krallık varsa hazır şeridi "x/20 hazır" sayacı ve 4 arma
  gösteriyor; krallık adı sıkışmıyor.
- **Ölçüm:** 600 bölge, 20 krallık için yakınlaştırma adımı başına ortalama 2.0 ms
  (Robolectric/JVM; cihaz ölçümü değil).
