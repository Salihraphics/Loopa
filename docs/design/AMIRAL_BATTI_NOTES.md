# Amiral Battı v2 (0.9.5): deniz komuta merkezi

## Kullanıcı kararları (2026-11-15)

- **Tekrar atış:** isabet eden oyuncu tekrar atar, ıskalayınca sıra geçer. 0.8.0'da bu kural
  yoktu; 1v1'de turda tek atış vardı, 2v2'de takım turda iki atış yapıyordu.
- **Joker kuralları:** ana oyundaki jokerlerle aynı.
  - Başlangıçta her oyuncuya 1 joker verilir.
  - Gemi batıran oyuncu +1 joker alır.
  - Envanter en fazla 3 jokerdir; doluysa yeni joker verilmez.
  - Ödül herkese duyurulur ama jokerin türü yalnızca sahibine görünür. Tür, joker
    kullanılınca açılır.
- **Torpido:** seçilen satır ya da sütun boyunca ilerler, ilk vurulmamış gemi parçasını vurur.
  Geçtiği boş suyu işaretlemez. Hatta gemi yoksa ıska sayılır.
- **Atış hakkı:** Sonar ve Keşif Uçağı atış hakkını harcamaz. Çifte Atış, Torpido ve Topçu
  Salvosu atışın yerine geçer; içlerinde isabet varsa oyuncu yine atar. Turda en fazla bir
  joker kullanılabilir.

## Sunucu

`supabase/migrations/20261123000001_amiral_batti_v2.sql`
(geri alma: `supabase/rollback/20261123000001_amiral_batti_v2_down.sql`).
Hosted'a **uygulanmadı**; onay bekliyor.

### Değişen

- **Sıra:** isabette oyuncu tekrar atar, ıskada sıra karşı tarafa geçer.
  - 2v2'de takımın sırası geldiğinde üyelerden biri atar ve isabet ettikçe atmaya devam eder.
  - Takımın sonraki sırasını diğer üye başlatır (state `starter`).
- **Jokerler:** `joker` eylemiyle kullanılır. Yük `{id, ...hedef}`.
  - **sonar** `{x, y}`: o noktanın çevresindeki 3×3 alanda vurulmamış gemi parçası var mı?
  - **recon** `{axis, i}`: seçilen satır ya da sütunda vurulmamış gemi parçası var mı?
  - **double** `{cells: [[x,y], [x,y]]}`: iki farklı, daha önce atılmamış kare.
  - **torpedo** `{axis, i, rev}`: satır ya da sütun boyunca, `rev` ile ters yönden.
  - **salvo** `{x, y, dir}`: düz bir hatta 3 kare. Daha önce atılmış kareler atlanır; en az
    biri yeni olmalı.
  - Hedef geçersizse joker harcanmaz.
- **Yeni hata kodları:** `JOKER_LIMIT` (bu turda zaten joker kullanıldı) ve
  `JOKER_NOT_AVAILABLE` (joker elinde değil). İstemcideki karşılıkları `GameLogic.kt`'ye eklendi.

### Gizlilik

- **Herkese açık durum:** her oyuncunun joker sayısı (`jk_count`) ve bu turda joker kullanan
  (`jk_turn`). Kayıtta joker ödülü (türsüz), joker kullanımı (türlü) ve her atışın sonucu yer
  alır.
- **Oyuncuya özel:** kendi jokerleri (türleriyle) ve takımının sonar ve keşif sonuçları
  (`intel`). Rakip takım bu sonuçları görmez.
- **Değişmeyen:** filolar oyun bitene kadar yalnızca sahibine (2v2'de takıma) görünür.

### Değişmeyen

Tahta boyutları, filolar, yerleştirme ve doğrulaması, isabet / ıska / batma, yenilgi koşulu,
puanlar, istatistikler ve yerleştirme süresi dolunca rastgele filo aynı. Sıra süresi dolarsa
sunucu sıradaki oyuncu için rastgele bir atış yapar; bu da önceden olduğu gibi.

### Testler

- `supabase/tests/amiral_batti_test.sql` (yeni): tekrar atış, ıskada sıra geçmesi, 2v2 üye
  dönüşü, başlangıç jokerleri, batırma ödülü, dolu envanter, beş jokerin her biri, geçersiz
  hedefte joker korunması, tek kullanımlık joker, tur sınırı, gizlilik, zaman aşımı, joker ile
  oyunu bitirme.
- `turn_games_test.sql`: eski 2v2 "takım başına iki atış" kontrolleri yeni dosyaya taşındı.

## İstemci

Dosyalar:
- `feature-game/.../modes/AmiralBattiArt.kt`: okyanus, ızgara, gemi silüetleri, işaretler ve
  efektler.
- `feature-game/.../modes/AmiralBattiScreen.kt`: ekran.

- **Tek harita, sırayı takip eder.**
  - Kendi takımının sırasında rakip sular gösterilir; oyuncu hedef seçip "ATEŞ"e basar.
  - Rakibin sırasında oyuncunun kendi filosu ve rakibin atışları gösterilir.
  - Sıra değiştiğinde son efektin bitmesi için yaklaşık 1,1 sn beklenir, sonra harita değişir
    ve "SIRA SENDE" / "RAKİP ATEŞ EDİYOR" bandı görünür.
  - Oyun bitince iki filo da açılır; "Rakip sular" / "Senin filon" düğmeleriyle yine tek harita
    gösterilir.
- **Görsel kimlik:** koyu lacivert zemin, cyan vurgular, turuncu isabetler, açık gri ıskalar.
  Haritada hafif dalgalanan su, A–J ve 1–10 koordinatları, köşe çerçeveleri ve hedef seçerken
  hafif bir radar taraması var.
- **Gemiler:** üstten görünüşlü özgün silüetler.
  - Uçak gemisi: pist, ada yapısı ve uçaklar.
  - Zırhlı: üç taret.
  - Kruvazör: iki taret.
  - Denizaltı: kule ve dalış dümenleri.
  - Muhrip: tek taret.
  - Tür, uzunluktan çıkarılıyor; 3 karelik gemiler sırayla kruvazör ve denizaltı oluyor.
- **Animasyonlar:**
  - Hedef nişangâhı.
  - Iska: sıçrama efekti.
  - İsabet: patlama, kıvılcımlar ve kısa ekran sarsıntısı.
  - Kalıcı işaretler: ıskada X, isabette yanık izi ve titreyen alev.
  - Batan gemi incelir, solar, kabarcık ve halkalar çıkar.
  - Jokerler: sonar halkaları, keşif uçağının hat boyunca geçişi, torpido izi; salvo ve çifte
    atış ayrı ayrı sıçrama ya da patlama gösterir.
  - Efektler yalnızca sunucudan gelen yeni atış, batma ve joker kayıtlarından üretilir.
    Hareket azaltma açıksa efekt çalışmaz; kalıcı işaretler yine görünür.
- **Joker arayüzü:**
  - Ekranda 3 yuvalı bir joker tepsisi var. Bir joker seçilince hedefleme paneli açılır:
    açıklama, satır/sütun, yön (torpidoda baştan ya da sondan), "TARA" ya da "ATEŞ" ve
    "VAZGEÇ".
  - Seçilecek alan haritada önizlenir; geçersiz hedefte düğme kapalı kalır.
  - Bir joker kazanıldığında bildirim çıkar; kendi jokerinde türü yazar.
  - Sonar ve keşif sonuçları haritada kesikli alanlar olarak gösterilir ("TEMAS VAR" / temiz).
- **Üst bilgi alanı:** durum satırı, 1v1 / 2v2 etiketi, takım kartları (sıradaki oyuncu ▶ ile,
  joker sayısı ◆ ile) ve düşman ile kendi filonun kalan gemi sayısı.
- **Yerleştirme:**
  - Gemiyi tepsiden haritaya sürükleyebilir ya da seçip bir kareye dokunabilirsin.
  - Haritadaki gemiyi sürükleyerek taşıyabilirsin.
  - "DÖNDÜR" düğmesi var.
  - Geçersiz konumda gölge kırmızı olur ve uyarı çıkar.
  - Bütün gemiler yerleşmeden "HAZIR" düğmesi kapalı kalır. "RASTGELE" ve "TEMİZLE" de var.
  - 2v2'de takımın tek bir filosu olduğu yazıyor.
- **Ekran boyutu:** telefonda harita genişliği doldurur, kontroller altta durur. Tablette
  (genişlik ≥ 720dp ve yatay) harita ekran yüksekliğini kullanır; yanında komuta sütunu, hedef,
  jokerler, istihbarat ve seyir defteri bulunur. Amiral Battı bu yüzden 640dp içerik sınırının
  dışında tutuldu.

### UI testleri

`AmiralBattiScreenTest`, `AmiralBattiTabletTest`, `AmiralBattiSmallScreenTest` (10 test).
Fikstürler yerel sunucuda oynanmış gerçek oyunlardan alındı: yerleştirme, kendi sıram, rakibin
sırası, 2v2, oyun sonu.

## Açık noktalar

- **Bağlantı kopması ve yeniden bağlanma:** ekran her durumu sunucudan gelen anlık görüntüden
  çiziyor ve oda sistemi değişmedi. Durum değişince haritanın doğru çizildiği test edildi; ancak
  ağ kesilip yeniden bağlanma senaryosu uçtan uca test edilmedi.
- **Gerçek cihaz:** ses, titreşim ve animasyon akıcılığı gerçek cihazda denenmedi.
- **Süren oyunlar:** migration sırasında süren oyunlar yeni kurallarla devam eder. Başlangıç
  jokeri yalnızca yeni başlayan savaşlarda verilir.
