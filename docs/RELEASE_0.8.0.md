# LOOPA 0.8.0 — sürüm notları

Sürüm: `versionName 0.8.0`, `versionCode 8`.

## Yeni oyunlar (9)
Kurallar sunucuda (PL/pgSQL) uygulanır. Zar, karıştırma, gizli eller, hamle doğrulama ve sonuçları sunucu belirler.

| Oyun | Oyuncu | Sunucu | İstemci ekranı |
|---|---|---|---|
| Tavla | 2 | `20261120000002_turn_games.sql` (mars, en çok zar kuralı, büyük zar kuralı, 1/3/5 puan) | `TavlaScreen` |
| 21 | 2 | aynı dosya. **Bahis yok**: yalnızca "kart çek" ve "dur" eylemleri var | `YirmiBirScreen` |
| Kızma Birader | 2–4 | aynı dosya | `KizmaBiraderScreen` |
| Amiral Battı | 1v1 / 2v2 | aynı dosya (2v2: 14×14, takım başına 2 atış) | `AmiralBattiScreen` |
| Renk Kartları | 2–8 | `…03_card_games.sql`: TEMİZLE, AYNA, GAMBLE, ELİNİ GÖRELİM, +2/+4 üst üste, el Joker ile bitirilemez | `RenkKartlariScreen` |
| 101 Okey | 4 | `…04_okey.sql` (101 puan / 5 çift ile açma, işleme, cezalar) | `OkeyScreen` |
| Milyoner | 2–6 | `…05_milyoner.sql` (özgün tahta; para yalnızca maç içinde) | `MilyonerScreen` |
| Kurt Adam | 6–20 | `…07_kurt_adam.sql` (17 rol, kararlaştırılan dağılım ve gece sırası) | `KurtAdamScreen` |
| Krallıklar | 4–20 | `…08_kralliklar.sql` (denge değerleri geçici: `docs/design/KRALLIKLAR_NOTES.md`) | `KralliklarScreen` |

## Platform
- **LOOPA Coin**
  - Defter (`coin_transactions`) tutulur; güncellemeler atomiktir.
  - Bakiye negatife düşemez.
  - Aynı ödül iki kez verilemez (`unique(user_id, source_key)`).
  - Coin yalnızca kozmetik almak için kullanılır.
- **Maç ödülleri.** Oyun sınıfına göre coin verilir. Ödülsüz kalan durumlar:
  - tek kişilik maç,
  - maçtan erken ayrılma,
  - çok kısa maç,
  - günlük sınırın aşılması,
  - aynı rakiplerle sık oynama.
- **Seriler**
  - Bireysel ve takım serisi tutulur.
  - Çarpanlar yapılandırılabilir ve **toplanmaz**: ikisinden yüksek olan uygulanır.
- **XP, seviye, prestij, itibar**
  - XP hiç harcanmaz.
  - Prestij seviye 100'de açılır.
- **Görevler, rozetler, sıralama**
  - Günlük ve haftalık görevler; ödül "al" düğmesiyle alınır.
  - Rozetler ve ünvanlar vitrinde gösterilir. Admin yeni rozet ekleyebilir.
  - Haftalık ve toplam XP sıralaması tutulur.
- **Takımlar.** Kurma, kodla katılma ve ayrılma desteklenir.
- **Turnuvalar**
  - Biçimler: 1v1, takım ve herkes tek (FFA).
  - Sonuçları yalnızca sunucudaki maç verisi belirler.
  - Ödül alma koşulları:
    - en az 4 farklı katılımcı,
    - finalin gerçekten oynanmış olması,
    - günlük sınırın aşılmamış olması,
    - aynı katılımcı grubunun 24 saat içinde ödül almamış olması.
- **Yeni oda akışı**
  - Sıra: oyunu seç → oda açılır (`configured=false`) → oda sahibi ayarlar → herkes hazır olur → başlar.
  - Bu akış yalnızca 0.8 oyunlarında uygulanır. Eski 10 oyun eskisi gibi hemen hazırdır.
  - Oyun başına seçenekler sunucunun kabul ettiği değerlerle sınırlıdır.
- **Diğer**
  - Oyun çarkı: tercihlere göre seçimi **sunucu** yapar; istemci yalnızca sonucu canlandırır.
  - Beceriye göre adil takım dengeleme vardır.
  - Oyun başına ayrıntılı istatistik tutulur.

## Bilinen sınırlamalar (dürüst durum)
**Cihazda test edilmedi.** Tüm yeni ekranlar derleniyor. Hiçbiri gerçek bir cihazda ya da emülatörde elle oynanmadı. Özellikle şu ekranlar gerçek dokunmatik kullanımda görülmeli:
- Okey ıstakası,
- Tavla tahtası,
- Krallıklar haritası (yakınlaştırma ve kaydırma).

**Uygulanmayanlar ve sınırlar**
- **Lobide seri gösterimi yok:** oyuncu satırında taç ve alev görünmüyor, çünkü oda durumu seri verisini içermiyor. Profilde ve sıralamada görünüyor.
- **Kurt Adam animasyonları basit:** kalkan, susturma, dönüşüm ve diriltme kısa bir simge animasyonundan ibaret. Azaltılmış hareket açıkken animasyon yok.
- **Krallıklar denge değerleri geçici.** Spesifikasyonda tanımı olmayan konular için yorum yapıldı; ayrıntılar `docs/design/KRALLIKLAR_NOTES.md` içinde.
- **Milyoner:** oyuncular arası mülk takası ve açık artırma yok; satın alınmayan mülk satışta kalır.
- **Yeni oyunlar herkese açık değil.** Hepsi `always_free=false` olarak tanımlı; Plus ya da haftalık rotasyon gerekir. Bu bir iş kararı; isterseniz değiştirilebilir.
- **Gerçek para kullanılmıyor.** Coin satın alma (gerçek para ile) yok, oyun avantajı satılmıyor.

## Test durumu (yerel, 2026-11-14)
- **SQL test paketi:** `supabase/tests/run_local_tests.sh`, Postgres 17, tümü geçti.
  - Yeni testler: platform, turn_games, card_games, tournament, kurt_adam, kralliklar.
  - Eşzamanlılık ve kilit sırası betikleri de bu pakette çalıştı ve geçti.
- **Gradle:** 156 test geçti; atlanan, başarısız ya da hatalı test yok.
  - Kapsam: core, core-network, core-data, feature-lobby ve app modüllerinin birim testleri.
  - Yerel Docker Supabase'e karşı E2E testleri de dahil, yeni `NewGamesE2ETest` ile birlikte:
    - kurulum akışı,
    - 21'in sonuna kadar oynanması (bahis eylemi yok),
    - Krallıklar'da gizli emirler ve sis,
    - coin ödülü,
    - platform okumaları,
    - coin'siz satın almanın reddedilmesi.
- **Derleme:** `app-debug.apk` ve `app-release-unsigned.apk` üretildi.
- **Ölçülmedi:** cihaz ya da emülatör üzerinde elle test, performans ölçümü. 0.8 oyunları için Compose UI testi yazılmadı.

## Hosted veritabanı — UYGULANDI (2026-11-14, kullanıcı onayıyla: "önce yedek, sonra hepsi")
1. **Yedek:** Mantıksal yedek yönetim API'si üzerinden, salt okuma sorgularıyla alındı ve kullanıcıya teslim edildi.
   - Kapsam: 44 tablonun tüm satırları, 165 fonksiyon tanımı, 25 RLS politikası, yetkiler, kısıtlar, indeksler ve trigger'lar.
   - Satır sayıları kaynakla tek tek karşılaştırıldı.
   - Dosya: `hosted_backup_pre_070_080_2026-11-14.tar.gz`, sha256 `c0804c34…f266`. Repoda değil, çünkü kullanıcı verisi içeriyor.
2. **Prova:** Hosted ile aynı şema durumundaki yerel kopyaya hosted'ın verisi yüklendi; 9 migration hatasız uygulandı.
3. **Uygulama:** Her migration ayrı bir transaction'da çalıştı ve `supabase_migrations.schema_migrations` kaydı aynı transaction'da yazıldı. Uygulananlar:
   - `20261116000001_social`
   - `20261120000001` … `20261120000008`
4. **Doğrulama:**
   - 24 migration kayıtlı; 424 fonksiyon ve 19 oyun var.
   - Mevcut veri değişmedi: XP toplamı 2070, 6 profil, 9 maç, 219 kozmetik.
   - Hiçbir iç (`_`) fonksiyon istemci rollerine açık değil.
   - Tüm tablolarda RLS açık.
   - `supabase/review/social_post_apply_checks.sql` kontrollerinin hepsi beklenen değerde.
   - PostgREST yeni RPC'leri görüyor.

Geri dönüş: 0.7 için `supabase/rollback/20261116000001_social_down.sql`. 0.8 için ayrı bir rollback betiği yok; gerekirse yedekten yeniden kurulur.

## Yayın
- Release APK **imzasız** üretildi, çünkü `keystore.properties` yok. Uydurma bir imza kullanılmadı.
