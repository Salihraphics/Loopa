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

## Hosted veritabanı
**Hosted Supabase'e hiçbir migration uygulanmadı.** Uygulama sırası ve her biri için ayrı onay gerekir:
1. `20261116000001_social.sql` (0.7.0; inceleme belgesi `docs/MIGRATION_REVIEW_0.7.0.md`)
2. `20261120000001_platform.sql`
3. `20261120000002_turn_games.sql`
4. `20261120000003_card_games.sql`
5. `20261120000004_okey.sql`
6. `20261120000005_milyoner.sql`
7. `20261120000006_tournaments.sql`
8. `20261120000007_kurt_adam.sql`
9. `20261120000008_kralliklar.sql`

Eski sunucuyla çalışma:
- 0.8.0 uygulaması eski sunucuya bağlanınca yeni özellikleri "sunucu desteklemiyor" diye gizler.
- Yenilikler ekranı, 0.8 özellikleri yalnızca `get_wallet` varsa duyurur.

## Yayın
- Release APK **imzasız** üretildi, çünkü `keystore.properties` yok. Uydurma bir imza kullanılmadı.
