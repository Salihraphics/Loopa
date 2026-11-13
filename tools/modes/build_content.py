#!/usr/bin/env python3
"""Builds supabase/migrations/20261115000002_mode_content.sql: the content pools of the 9 games.

All content was written for LOOPA (CC0). The migration is idempotent (unique (kind, md5(payload))).
Quiz options are shuffled with a fixed seed so the right answer is not always in the same place.

  python3 tools/modes/build_content.py
"""
import json, random, re, pathlib, unicodedata

OUT = pathlib.Path(__file__).resolve().parents[2] / "supabase/migrations/20261115000002_mode_content.sql"

# ---------------------------------------------------------------- Gizli Kelime (described aloud)
WORDS = {
    "Ev": "Buzdolabı Çamaşır makinesi Halı Perde Yastık Yorgan Ayna Merdiven Balkon Şemsiye Süpürge Çaydanlık Tencere Kumanda Priz Anahtar Pencere Koltuk Dolap Lamba",
    "Doğa": "Gökkuşağı Şelale Volkan Çöl Orman Mağara Ada Yıldırım Kar tanesi Deprem Çığ Kumsal Vadi Bulut Rüzgar Göl Nehir Ay tutulması Kutup Toprak",
    "Yemek": "Lahmacun Baklava Mantı Simit Dondurma Menemen Kumpir Pilav Karpuz Patlamış mısır Turşu Kokoreç Künefe Sucuk Pizza Hamburger Mercimek çorbası Ayran Lokum Börek",
    "Meslek": "Doktor İtfaiyeci Pilot Aşçı Öğretmen Polis Berber Astronot Dişçi Garson Postacı Fotoğrafçı Hakem Kaptan Veteriner Terzi Bekçi Mimar Avukat Çiftçi",
    "Spor": "Futbol Basketbol Voleybol Yüzme Satranç Tenis Bisiklet Kayak Okçuluk Güreş Boks Maraton Kaykay Dalış Masa tenisi Halter Golf Sörf Eskrim Bowling",
    "Ulaşım": "Uçak Helikopter Denizaltı Tren Vapur Teleferik Tramvay Metro Kamyon Ambulans Taksi Motosiklet Balon Roket Kano Otobüs Traktör Yat Minibüs Paraşüt",
    "Eğlence": "Doğum günü Düğün Konser Sinema Lunapark Hediye Pasta Balon Karaoke Piknik Kamp Tatil Bayram Havai fişek Sihirbaz Palyaço Masal Bulmaca Saklambaç Yılbaşı",
}
WORD_ALIASES = {"Uçak": ["tayyare"], "Otobüs": ["otobus"], "Dişçi": ["diş hekimi", "dis doktoru", "diş doktoru"],
                "Doktor": ["hekim"], "Buzdolabı": ["buzdolabi"], "Kumanda": ["uzaktan kumanda"], "Futbol": ["ayak topu"],
                "Polis": ["komiser"], "Gökkuşağı": ["gökkusagi"], "Bisiklet": ["bisikletçi"]}

# ---------------------------------------------------------------- Çiz ve Tahmin Et (drawable)
DRAW = {
    "Hayvan": "Kedi Köpek Fil Zürafa Balık Kaplumbağa Yılan Kelebek Örümcek Penguen Tavşan Aslan Ahtapot Baykuş Salyangoz Deve Kartal Fare Arı Timsah",
    "Eşya": "Gözlük Saat Şapka Ayakkabı Makas Kalem Telefon Gitar Şemsiye Anahtar Bardak Çanta Kitap Mum Fener Çekiç Top Kaşık Yastık Davul",
    "Yiyecek": "Elma Muz Çilek Karpuz Havuç Ekmek Pizza Dondurma Yumurta Peynir Kiraz Ananas Mısır Armut Üzüm Limon Pasta Kurabiye Biber Mantar",
    "Doğa": "Güneş Ay Yıldız Bulut Ağaç Çiçek Dağ Şimşek Kar tanesi Yaprak Gökkuşağı Dalga Volkan Ada Kaktüs Kalp Ateş Damla Taş Yağmur",
    "Yerler": "Ev Kale Köprü Deniz feneri Çadır Okul Hastane Cami Piramit Kule Ahır Liman Çeşme Havuz Park Saray Mağara Bahçe Stadyum Dolmuş",
    "Taşıt": "Araba Uçak Gemi Tren Bisiklet Roket Helikopter Kayık Otobüs Balon Kamyon Motosiklet Denizaltı Traktör Paten Kızak Tank Uzay mekiği Vapur Kaykay",
}

# ---------------------------------------------------------------- Sessiz Sinema (acted without speaking)
CHARADES = {
    "Film": ["Titanik", "Yüzüklerin Efendisi", "Harry Potter", "Aslan Kral", "Örümcek Adam", "Karayip Korsanları", "Buz Devri",
             "Hababam Sınıfı", "Şaban Oğlu Şaban", "Babam ve Oğlum", "Eşkıya", "Dönersen Islık Çal", "Kuzular Sessiz Kalmasın",
             "Jurassic Park", "Matrix", "Yıldız Savaşları", "Oyuncak Hikayesi", "Kayıp Balık Nemo", "Karlar Ülkesi", "Recep İvedik"],
    "Atasözü": ["Damlaya damlaya göl olur", "Sakla samanı gelir zamanı", "Ayağını yorganına göre uzat", "Üzüm üzüme baka baka kararır",
                "İşleyen demir ışıldar", "Dost acı söyler", "Ağaç yaşken eğilir", "Bakarsan bağ olur bakmazsan dağ olur",
                "Komşu komşunun külüne muhtaçtır", "Sütten ağzı yanan yoğurdu üfleyerek yer", "Tatlı dil yılanı deliğinden çıkarır",
                "Her yokuşun bir inişi vardır", "Acele işe şeytan karışır", "Çok bilen çok yanılır", "Bin bilsen de bir bilene danış"],
    "Hareket": ["Diş fırçalamak", "Bulaşık yıkamak", "Ütü yapmak", "Balık tutmak", "Kek yapmak", "Fotoğraf çekmek", "Halat çekmek",
                "Araba sürmek", "Selfie çekmek", "Kayak yapmak", "Çadır kurmak", "Saç kesmek", "Uçurtma uçurmak", "Dans etmek",
                "Kar topu oynamak", "Ip atlamak", "Yüzme", "Halay çekmek", "Gitar çalmak", "Pizza açmak"],
    "Meslek": ["Dalgıç", "Hakem", "Aşçı", "Pilot", "Sihirbaz", "Boksör", "Orkestra şefi", "Garson", "Berber", "Dişçi",
               "Fotoğrafçı", "İtfaiyeci", "Bale dansçısı", "Mim sanatçısı", "Astronot", "Bahçıvan", "Ressam", "Kasiyer"],
    "Hayvan": ["Penguen", "Kanguru", "Maymun", "Fil", "Yılan", "Tavuk", "Ahtapot", "Zürafa", "Kurbağa", "Kartal",
               "Ayı", "Yengeç", "Tavus kuşu", "Kedi", "Köpek balığı", "Kaplumbağa", "Arı", "Yunus"],
}

# ---------------------------------------------------------------- Anlat Bakalım (word + 5 forbidden words)
TABOO = [
    ("Deniz", "su mavi dalga plaj tuz"), ("Okul", "öğretmen öğrenci ders sınıf teneffüs"), ("Kahve", "türk fincan içmek kafein telve"),
    ("Futbol", "top gol kale maç takım"), ("Telefon", "aramak mesaj ekran şarj cep"), ("Kar", "beyaz kış soğuk yağmak kardan adam"),
    ("Doktor", "hastane hasta ilaç muayene hekim"), ("Uçak", "havaalanı pilot uçmak bilet kanat"), ("Çay", "demlik bardak içmek şeker rize"),
    ("Kitap", "okumak sayfa yazar kütüphane roman"), ("Güneş", "sıcak yaz ışık gökyüzü sarı"), ("Düğün", "gelin damat evlenmek nikah halay"),
    ("Pizza", "italyan peynir hamur dilim fırın"), ("Bayram", "şeker tatil el öpmek harçlık kurban"), ("Saat", "zaman kol dakika akrep yelkovan"),
    ("Sinema", "film perde bilet patlamış mısır salon"), ("Yağmur", "su bulut şemsiye ıslak damla"), ("Diş", "ağız fırça dişçi çürük gülümsemek"),
    ("Simit", "susam halka çay fırın sokak"), ("Bisiklet", "pedal tekerlek sürmek zincir kask"), ("Ayna", "yansıma bakmak cam yüz duvar"),
    ("Kedi", "miyav fare tüy pati evcil"), ("Tatil", "yaz deniz otel izin gezmek"), ("Müzik", "şarkı dinlemek nota çalmak ses"),
    ("Ekmek", "fırın un dilim kahvaltı somun"), ("Gözlük", "göz görmek cam numara takmak"), ("Tren", "ray istasyon vagon bilet makinist"),
    ("Dondurma", "soğuk külah yaz top çikolata"), ("Ağaç", "yaprak dal orman meyve gövde"), ("Bilgisayar", "ekran klavye fare internet yazılım"),
    ("Yumurta", "tavuk kahvaltı sarı kabuk haşlamak"), ("Balık", "deniz yüzmek olta pul tutmak"), ("Hastane", "doktor hemşire hasta ameliyat acil"),
    ("Futbolcu", "top maç forma gol takım"), ("Piknik", "çimen sepet doğa mangal örtü"), ("Doğum günü", "pasta mum yaş hediye kutlama"),
    ("Kış", "kar soğuk mevsim aralık mont"), ("Ay", "gece dolunay gökyüzü uydu hilal"), ("Uyku", "yatak gece rüya yorgun uyumak"),
    ("Polis", "suç hırsız karakol düdük üniforma"), ("Market", "alışveriş kasa raf poşet fiyat"), ("Araba", "teker sürmek benzin direksiyon yol"),
    ("Kahvaltı", "sabah peynir zeytin çay yumurta"), ("Bal", "arı tatlı kovan petek sarı"), ("Fotoğraf", "çekmek kamera poz albüm resim"),
    ("Mektup", "zarf posta yazmak pul göndermek"), ("Kalem", "yazmak kurşun mürekkep defter silgi"), ("Şemsiye", "yağmur açmak ıslanmak sap tutmak"),
    ("Hamam", "kese köpük sıcak tellak kurna"), ("Lunapark", "dönme dolap eğlence oyuncak bilet çarpışan"), ("Orman", "ağaç yeşil hayvan yürüyüş kamp"),
    ("Gitar", "tel çalmak müzik akor tezene"), ("Bulut", "gökyüzü beyaz yağmur pamuk gri"), ("Pasta", "doğum günü krema mum dilim tatlı"),
    ("Ambulans", "hastane siren acil hasta sedye"), ("Plaj", "kum deniz güneş havlu şezlong"), ("Köprü", "geçmek nehir boğaz karşı yol"),
    ("Rüya", "uyku gece görmek kabus yatak"), ("Hediye", "paket vermek doğum günü sürpriz kurdele"), ("Kütüphane", "kitap okumak sessiz raf ödünç"),
]

# ---------------------------------------------------------------- Bilgi Arenası
# (category, question, correct, [wrong x3], explanation)
QUESTIONS = [
    ("Coğrafya", "Türkiye'nin başkenti hangisidir?", "Ankara", ["İstanbul", "İzmir", "Bursa"], "Ankara, 13 Ekim 1923'ten beri başkenttir."),
    ("Coğrafya", "Türkiye'nin en yüksek dağı hangisidir?", "Ağrı Dağı", ["Erciyes Dağı", "Uludağ", "Palandöken"], "Ağrı Dağı yaklaşık 5.137 metredir."),
    ("Coğrafya", "Türkiye'nin yüzölçümü en büyük gölü hangisidir?", "Van Gölü", ["Tuz Gölü", "Beyşehir Gölü", "Eğirdir Gölü"], "Van Gölü yaklaşık 3.700 km² ile en büyük gölümüzdür."),
    ("Coğrafya", "Dünyanın en büyük okyanusu hangisidir?", "Büyük Okyanus (Pasifik)", ["Atlas Okyanusu", "Hint Okyanusu", "Arktik Okyanusu"], "Büyük Okyanus, yeryüzünün yaklaşık üçte birini kaplar."),
    ("Coğrafya", "Avustralya'nın başkenti hangisidir?", "Kanberra", ["Sidney", "Melbourne", "Perth"], "Sidney en kalabalık şehirdir, başkent ise Kanberra'dır."),
    ("Coğrafya", "Kanada'nın başkenti hangisidir?", "Ottava", ["Toronto", "Vancouver", "Montreal"], "Kanada'nın başkenti Ottava'dır."),
    ("Coğrafya", "Japonya'nın para birimi nedir?", "Yen", ["Won", "Yuan", "Rupi"], "Won Kore'nin, yuan Çin'in para birimidir."),
    ("Coğrafya", "Yüzölçümü en büyük ülke hangisidir?", "Rusya", ["Kanada", "Çin", "ABD"], "Rusya, 17 milyon km²'yi aşan yüzölçümüyle birincidir."),
    ("Coğrafya", "Mısır'ın başkenti hangisidir?", "Kahire", ["İskenderiye", "Gize", "Luksor"], "Kahire, Nil Nehri kıyısındadır."),
    ("Coğrafya", "İstanbul hangi iki kıtada yer alır?", "Avrupa ve Asya", ["Avrupa ve Afrika", "Asya ve Afrika", "Yalnızca Avrupa"], "İstanbul Boğazı iki kıtayı ayırır."),
    ("Coğrafya", "Türkiye'de kaç il vardır?", "81", ["79", "80", "82"], "Son iller Kilis, Osmaniye ve Düzce ile sayı 81'e ulaştı."),
    ("Coğrafya", "Hangi il Karadeniz kıyısındadır?", "Trabzon", ["Konya", "Eskişehir", "Malatya"], "Trabzon, Doğu Karadeniz'in kıyı illerindendir."),
    ("Coğrafya", "Everest Dağı hangi sıradağlardadır?", "Himalayalar", ["Alpler", "And Dağları", "Kayalık Dağlar"], "Everest, dünyanın en yüksek dağıdır."),
    ("Coğrafya", "Brezilya'nın resmî dili nedir?", "Portekizce", ["İspanyolca", "Fransızca", "İtalyanca"], "Brezilya bir Portekiz sömürgesiydi."),
    ("Coğrafya", "Sahra Çölü hangi kıtadadır?", "Afrika", ["Asya", "Güney Amerika", "Avustralya"], "Sahra, dünyanın en büyük sıcak çölüdür."),
    ("Coğrafya", "İtalya'nın başkenti hangisidir?", "Roma", ["Milano", "Venedik", "Napoli"], "Roma, İtalya'nın başkenti ve en kalabalık şehridir."),
    ("Coğrafya", "Pamukkale travertenleri hangi ildedir?", "Denizli", ["Aydın", "Muğla", "Antalya"], "Pamukkale, UNESCO Dünya Mirası listesindedir."),
    ("Coğrafya", "Tamamı Türkiye'de olan en uzun nehir hangisidir?", "Kızılırmak", ["Fırat", "Sakarya", "Yeşilırmak"], "Kızılırmak yaklaşık 1.355 km uzunluğundadır."),
    ("Coğrafya", "Hangisi bir kıta değildir?", "Grönland", ["Asya", "Afrika", "Avrupa"], "Grönland dünyanın en büyük adasıdır, kıta değildir."),
    ("Coğrafya", "Penguenler doğal olarak çoğunlukla hangi yarımkürede yaşar?", "Güney Yarımküre", ["Kuzey Yarımküre", "Her iki kutupta eşit", "Yalnızca ekvatorda"], "Penguenler neredeyse tamamen Güney Yarımküre'de yaşar."),
    ("Coğrafya", "Plaka kodu 06 olan il hangisidir?", "Ankara", ["İstanbul", "İzmir", "Antalya"], "34 İstanbul, 35 İzmir, 07 Antalya'dır."),
    ("Coğrafya", "Fransa'nın başkenti hangisidir?", "Paris", ["Lyon", "Marsilya", "Nice"], "Eyfel Kulesi de Paris'tedir."),
    ("Tarih", "TBMM hangi yıl açıldı?", "1920", ["1919", "1921", "1923"], "Türkiye Büyük Millet Meclisi 23 Nisan 1920'de açıldı."),
    ("Tarih", "Cumhuriyet hangi yıl ilan edildi?", "1923", ["1920", "1922", "1924"], "Cumhuriyet 29 Ekim 1923'te ilan edildi."),
    ("Tarih", "İstanbul hangi yıl fethedildi?", "1453", ["1071", "1299", "1517"], "İstanbul'u Fatih Sultan Mehmet fethetti."),
    ("Tarih", "Malazgirt Meydan Muharebesi hangi yıl yapıldı?", "1071", ["1176", "1453", "1923"], "Sultan Alparslan, Bizans ordusunu yendi."),
    ("Tarih", "Osmanlı Devleti'nin kurucusu kimdir?", "Osman Bey", ["Orhan Bey", "Ertuğrul Gazi", "I. Murad"], "Devlet adını kurucusu Osman Bey'den alır."),
    ("Tarih", "Türkiye Cumhuriyeti'nin ilk cumhurbaşkanı kimdir?", "Mustafa Kemal Atatürk", ["İsmet İnönü", "Celal Bayar", "Fevzi Çakmak"], "Atatürk 29 Ekim 1923'te cumhurbaşkanı seçildi."),
    ("Tarih", "Ay'a ilk ayak basan insan kimdir?", "Neil Armstrong", ["Buzz Aldrin", "Yuri Gagarin", "Michael Collins"], "Armstrong 1969'da Apollo 11 ile Ay'a ayak bastı."),
    ("Tarih", "Uzaya çıkan ilk insan kimdir?", "Yuri Gagarin", ["Neil Armstrong", "Valentina Tereşkova", "John Glenn"], "Gagarin 1961'de Vostok 1 ile uzaya çıktı."),
    ("Tarih", "Büyük Taarruz hangi yıl yapıldı?", "1922", ["1919", "1920", "1923"], "Büyük Taarruz Ağustos 1922'de başladı."),
    ("Tarih", "Türk kadınları milletvekili seçme ve seçilme hakkını hangi yıl kazandı?", "1934", ["1923", "1930", "1946"], "1930'da yerel seçim, 1934'te milletvekili seçme ve seçilme hakkı tanındı."),
    ("Tarih", "Latin harflerine dayalı yeni Türk alfabesi hangi yıl kabul edildi?", "1928", ["1923", "1925", "1934"], "Harf İnkılabı 1 Kasım 1928'de yasalaştı."),
    ("Tarih", "Birinci Dünya Savaşı hangi yıl başladı?", "1914", ["1905", "1918", "1939"], "Savaş 1914'te başlayıp 1918'de sona erdi."),
    ("Tarih", "İkinci Dünya Savaşı hangi yıl sona erdi?", "1945", ["1939", "1944", "1950"], "Savaş 1939'da başladı, 1945'te bitti."),
    ("Tarih", "Mimar Sinan'ın 'ustalık eserim' dediği cami hangisidir?", "Selimiye Camii", ["Süleymaniye Camii", "Şehzade Camii", "Sultanahmet Camii"], "Selimiye Camii Edirne'dedir."),
    ("Tarih", "Truva antik kenti bugün hangi ilimizdedir?", "Çanakkale", ["İzmir", "Balıkesir", "Aydın"], "Truva, UNESCO Dünya Mirası listesindedir."),
    ("Tarih", "Çanakkale Kara Savaşları hangi yıl yapıldı?", "1915", ["1912", "1918", "1921"], "18 Mart 1915 deniz zaferinin ardından kara savaşları yaşandı."),
    ("Tarih", "Soyadı Kanunu hangi yıl kabul edildi?", "1934", ["1926", "1928", "1938"], "Soyadı Kanunu 21 Haziran 1934'te kabul edildi."),
    ("Bilim", "Suyun kimyasal formülü nedir?", "H₂O", ["CO₂", "O₂", "NaCl"], "Bir su molekülü iki hidrojen ve bir oksijen atomundan oluşur."),
    ("Bilim", "Güneş Sistemi'nin en büyük gezegeni hangisidir?", "Jüpiter", ["Satürn", "Dünya", "Neptün"], "Jüpiter bir gaz devidir."),
    ("Bilim", "'Kızıl Gezegen' olarak bilinen gezegen hangisidir?", "Mars", ["Venüs", "Merkür", "Jüpiter"], "Yüzeyindeki demir oksit Mars'a kırmızı rengini verir."),
    ("Bilim", "İnsan vücudunun en büyük organı hangisidir?", "Deri", ["Karaciğer", "Akciğer", "Beyin"], "Deri, vücudu kaplayan en büyük organdır."),
    ("Bilim", "Işığın boşluktaki hızı yaklaşık ne kadardır?", "300.000 km/s", ["30.000 km/s", "3.000 km/s", "3.000.000 km/s"], "Işık saniyede yaklaşık 300 bin kilometre yol alır."),
    ("Bilim", "Deniz seviyesinde saf su kaç °C'de kaynar?", "100", ["90", "80", "120"], "Yükseklik arttıkça kaynama sıcaklığı düşer."),
    ("Bilim", "Elmas hangi elementten oluşur?", "Karbon", ["Silisyum", "Demir", "Kalsiyum"], "Grafit de karbondan oluşur; fark atomların dizilişindedir."),
    ("Bilim", "Yetişkin bir insanın iskeletinde kaç kemik bulunur?", "206", ["180", "250", "300"], "Bebeklerde daha fazla kemik vardır; bazıları zamanla kaynaşır."),
    ("Bilim", "Bitkilerin güneş ışığıyla besin üretmesine ne denir?", "Fotosentez", ["Solunum", "Sindirim", "Buharlaşma"], "Fotosentezde karbondioksit ve sudan şeker üretilir."),
    ("Bilim", "Periyodik tabloda 'O' sembolü hangi elementtir?", "Oksijen", ["Osmiyum", "Altın", "Ozon"], "Altının sembolü Au'dur."),
    ("Bilim", "Güneş'e en yakın gezegen hangisidir?", "Merkür", ["Venüs", "Mars", "Dünya"], "Merkür aynı zamanda en küçük gezegendir."),
    ("Bilim", "Evrensel çekim yasasıyla tanınan bilim insanı kimdir?", "Isaac Newton", ["Albert Einstein", "Galileo Galilei", "Nikola Tesla"], "Newton bu yasayı 1687'de yayımladı."),
    ("Bilim", "Kanı vücuda pompalayan organ hangisidir?", "Kalp", ["Akciğer", "Böbrek", "Mide"], "Kalp dakikada ortalama 60-100 kez atar."),
    ("Bilim", "Ses hangi ortamda yayılamaz?", "Boşluk", ["Su", "Hava", "Demir"], "Ses titreşimle yayılır; boşlukta titreşecek madde yoktur."),
    ("Bilim", "Hangisi bir memelidir?", "Yunus", ["Köpek balığı", "Ahtapot", "Deniz kaplumbağası"], "Yunuslar akciğerle nefes alır ve yavrularını emzirir."),
    ("Bilim", "En hafif element hangisidir?", "Hidrojen", ["Helyum", "Oksijen", "Lityum"], "Hidrojenin atom numarası 1'dir."),
    ("Bilim", "Belirgin halkalarıyla tanınan gezegen hangisidir?", "Satürn", ["Mars", "Merkür", "Venüs"], "Satürn'ün halkaları buz ve kaya parçalarından oluşur."),
    ("Bilim", "Bir yıl (artık yıl değilse) kaç gündür?", "365", ["360", "364", "366"], "Artık yıllar 366 gündür."),
    ("Bilim", "Gökkuşağında geleneksel olarak kaç renk sayılır?", "7", ["5", "6", "8"], "Kırmızı, turuncu, sarı, yeşil, mavi, lacivert ve mor."),
    ("Bilim", "Sarı ve mavi boya karıştırılınca hangi renk elde edilir?", "Yeşil", ["Mor", "Turuncu", "Kahverengi"], "Kırmızı ile mavi mor, kırmızı ile sarı turuncu verir."),
    ("Spor", "Bir futbol takımı sahaya kaç oyuncuyla çıkar?", "11", ["9", "10", "12"], "Kaleci dahil 11 oyuncu."),
    ("Spor", "Olimpiyat bayrağında kaç halka vardır?", "5", ["4", "6", "7"], "Beş halka birbirine geçmiştir."),
    ("Spor", "Basketbolda bir serbest atış kaç sayıdır?", "1", ["2", "3", "0"], "Üç sayı çizgisinin dışından yapılan basket 3 sayıdır."),
    ("Spor", "Voleybolda sahada her takımdan kaç oyuncu bulunur?", "6", ["5", "7", "8"], "Altı oyuncu dönerek pozisyon değiştirir."),
    ("Spor", "Satrançta oyunu bitiren hamleye ne denir?", "Şah mat", ["Rok", "Pat", "Gambit"], "Pat da oyunu bitirir ama sonuç beraberliktir."),
    ("Spor", "Wimbledon tenis turnuvası hangi zeminde oynanır?", "Çim", ["Toprak", "Sert kort", "Halı"], "Roland Garros toprak kortta oynanır."),
    ("Spor", "Maraton koşusu kaç kilometredir?", "42,195 km", ["21,1 km", "40 km", "50 km"], "Yarı maraton bunun yarısı kadardır."),
    ("Spor", "Kırkpınar yağlı güreşleri hangi ilde yapılır?", "Edirne", ["Bursa", "Manisa", "Kırklareli"], "Kırkpınar, yüzyıllardır süren bir gelenektir."),
    ("Spor", "FIFA Dünya Kupası kaç yılda bir düzenlenir?", "4", ["2", "3", "5"], "Dünya Kupası ilk kez 1930'da düzenlendi."),
    ("Spor", "Bir basketbol takımında sahada kaç oyuncu bulunur?", "5", ["6", "7", "4"], "Her takımdan beş oyuncu sahadadır."),
    ("Spor", "Türkiye 2002 FIFA Dünya Kupası'nı kaçıncı bitirdi?", "Üçüncü", ["Birinci", "İkinci", "Dördüncü"], "Türkiye üçüncülük maçında Güney Kore'yi yendi."),
    ("Spor", "Satranç tahtasında kaç kare vardır?", "64", ["48", "56", "81"], "Tahta 8'e 8 karedir."),
    ("Sanat", "Mona Lisa tablosunun ressamı kimdir?", "Leonardo da Vinci", ["Michelangelo", "Van Gogh", "Picasso"], "Tablo Paris'teki Louvre Müzesi'ndedir."),
    ("Sanat", "İstiklal Marşı'nın şairi kimdir?", "Mehmet Akif Ersoy", ["Namık Kemal", "Ziya Gökalp", "Tevfik Fikret"], "İstiklal Marşı 12 Mart 1921'de kabul edildi."),
    ("Sanat", "Nutuk'u kim yazmıştır?", "Mustafa Kemal Atatürk", ["İsmet İnönü", "Halide Edib Adıvar", "Ziya Gökalp"], "Atatürk Nutuk'u 1927'de okudu."),
    ("Sanat", "Fıkralarda Nasreddin Hoca'nın bindiği hayvan hangisidir?", "Eşek", ["At", "Deve", "Katır"], "Nasreddin Hoca, Akşehir ile özdeşleşmiştir."),
    ("Sanat", "Romeo ve Juliet'in yazarı kimdir?", "William Shakespeare", ["Charles Dickens", "Victor Hugo", "Molière"], "Oyun 1590'larda yazıldı."),
    ("Sanat", "Nobel Edebiyat Ödülü'nü kazanan Türk yazar kimdir?", "Orhan Pamuk", ["Yaşar Kemal", "Elif Şafak", "Nazım Hikmet"], "Orhan Pamuk ödülü 2006'da aldı."),
    ("Sanat", "Kürk Mantolu Madonna'nın yazarı kimdir?", "Sabahattin Ali", ["Ahmet Hamdi Tanpınar", "Oğuz Atay", "Peyami Safa"], "Roman 1943'te yayımlandı."),
    ("Sanat", "İnce Memed romanının yazarı kimdir?", "Yaşar Kemal", ["Orhan Kemal", "Kemal Tahir", "Fakir Baykurt"], "Roman Çukurova'da geçer."),
    ("Sanat", "Gölge oyununda Karagöz'ün arkadaşı kimdir?", "Hacivat", ["Keloğlan", "İbiş", "Pişekar"], "Pişekar ve Kavuklu ortaoyunu karakterleridir."),
    ("Sanat", "Don Kişot'un yazarı kimdir?", "Miguel de Cervantes", ["Dante Alighieri", "Lev Tolstoy", "Franz Kafka"], "Don Kişot 1605'te yayımlandı."),
    ("Sanat", "Bağlama hangi tür çalgıdır?", "Telli çalgı", ["Vurmalı çalgı", "Üflemeli çalgı", "Tuşlu çalgı"], "Bağlama, Türk halk müziğinin temel çalgısıdır."),
    ("Sanat", "Sefiller romanının yazarı kimdir?", "Victor Hugo", ["Émile Zola", "Alexandre Dumas", "Jules Verne"], "Roman 1862'de yayımlandı."),
    ("Sanat", "Beethoven hangi alanda tanınır?", "Bestecilik", ["Resim", "Heykel", "Mimarlık"], "Beethoven duyma yetisini kaybettikten sonra da beste yaptı."),
    ("Genel Kültür", "Bir asır kaç yıldır?", "100", ["10", "50", "1000"], "Bin yıla binyıl denir."),
    ("Genel Kültür", "Bir düzine kaç adettir?", "12", ["10", "6", "20"], "Düzine on iki tanedir."),
    ("Genel Kültür", "Türk bayrağındaki ay ve yıldız hangi renktedir?", "Beyaz", ["Sarı", "Kırmızı", "Lacivert"], "Bayrak kırmızı zemin üzerinde beyaz ay yıldızdır."),
    ("Genel Kültür", "Telefonun mucidi olarak bilinen kişi kimdir?", "Alexander Graham Bell", ["Thomas Edison", "Nikola Tesla", "Guglielmo Marconi"], "Bell 1876'da patent aldı."),
    ("Genel Kültür", "Akkor ampulü ticari olarak geliştirmesiyle bilinen mucit kimdir?", "Thomas Edison", ["Alexander Graham Bell", "James Watt", "Louis Pasteur"], "Edison'un ampulü 1879'da tanıtıldı."),
    ("Genel Kültür", "Pizzanın anavatanı hangi ülkedir?", "İtalya", ["Yunanistan", "Fransa", "ABD"], "Modern pizza Napoli'de ortaya çıktı."),
    ("Genel Kültür", "Artık yıllarda Şubat ayı kaç gündür?", "29", ["28", "30", "31"], "Artık yıllar genellikle dört yılda bir gelir."),
    ("Genel Kültür", "Cumhuriyet Bayramı hangi gün kutlanır?", "29 Ekim", ["23 Nisan", "19 Mayıs", "30 Ağustos"], "1923'te Cumhuriyet'in ilanını kutlarız."),
    ("Genel Kültür", "Ulusal Egemenlik ve Çocuk Bayramı hangi gündür?", "23 Nisan", ["29 Ekim", "19 Mayıs", "1 Mayıs"], "TBMM'nin açılış günüdür."),
    ("Genel Kültür", "Atatürk'ü Anma, Gençlik ve Spor Bayramı hangi gündür?", "19 Mayıs", ["23 Nisan", "30 Ağustos", "29 Ekim"], "Atatürk 19 Mayıs 1919'da Samsun'a çıktı."),
    ("Genel Kültür", "Zafer Bayramı hangi gün kutlanır?", "30 Ağustos", ["9 Eylül", "29 Ekim", "19 Mayıs"], "1922'deki Başkomutanlık Meydan Muharebesi'nin zaferidir."),
    ("Genel Kültür", "Gökyüzünde en parlak görünen yıldız (Güneş dışında) hangisidir?", "Sirius", ["Kutup Yıldızı", "Vega", "Betelgeuse"], "Sirius, Büyük Köpek takımyıldızındadır."),
    ("Genel Kültür", "Hangisi bir renk değil, bir mevsimdir?", "Sonbahar", ["Lacivert", "Bordo", "Turkuaz"], "Dört mevsim: ilkbahar, yaz, sonbahar, kış."),
    ("Genel Kültür", "Bir saat kaç dakikadır?", "60", ["30", "100", "90"], "Bir dakika da 60 saniyedir."),
]

# ---------------------------------------------------------------- En Muhtemel Kim?
PROMPTS = """Kim alarmı en çok erteler?
Kim bir yarışma programını kazanır?
Kim telefonunu en sık kaybeder?
Kim en uzun mesajı yazar?
Kim tatilde valizini en son dakikada hazırlar?
Kim sınavdan bir gece önce çalışmaya başlar?
Kim bir filmde ağlar?
Kim en iyi yemeği yapar?
Kim ünlü olur?
Kim bir hayvanat bahçesinde kaybolur?
Kim en çok kahve içer?
Kim bir şarkıyı yanlış sözlerle söyler?
Kim marketten listede olmayan şeyler alır?
Kim bir sırrı en uzun saklar?
Kim en çok selfie çeker?
Kim bir korku filminde ilk kaçar?
Kim yeni bir dili en hızlı öğrenir?
Kim randevuya geç kalır?
Kim en güzel dans eder?
Kim bir tartışmayı kazanır?
Kim uzaya gitmek ister?
Kim en çok gülümser?
Kim yolda en çok tanıdığa rastlar?
Kim bir sabah dağa tırmanmaya karar verir?
Kim en çok fotoğraf biriktirir?
Kim kendi işini kurar?
Kim en iyi hediyeyi seçer?
Kim bir bitkiyi yaşatamaz?
Kim karaokede mikrofonu bırakmaz?
Kim en çok atıştırmalık taşır?
Kim bir gün kitap yazar?
Kim bir tartışmada hemen özür diler?
Kim bir eşyayı tamir etmeye çalışıp daha çok bozar?
Kim oyunlarda en çok hırslanır?
Kim yeni çıkan teknolojiyi ilk alır?
Kim haritaya bakmadan yolu bulur?
Kim bir kedi kafesi kurar?
Kim gece yarısı buzdolabını açar?
Kim en çok bilgi yarışması sorusunu bilir?
Kim bir dizinin bütün sezonunu bir hafta sonunda bitirir?
Kim en iyi fıkrayı anlatır?
Kim bir yolculukta en çok uyur?
Kim doğum günlerini asla unutmaz?
Kim en çok kahkaha atar?
Kim bir yarışta son anda öne geçer?
Kim tatilde en çok hediyelik eşya alır?
Kim parti planlar?
Kim en çok kitap okur?
Kim bir gün dünya turuna çıkar?
Kim sabah ilk uyanan olur?
Kim bir hayvanla konuşur?
Kim bir şakaya en geç güler?
Kim en çok sporu sever?
Kim bir yemeğe en çok baharat koyar?
Kim zombi kıyametinde hayatta kalır?
Kim en çok soru sorar?
Kim bir sinema salonunda sessiz kalamaz?
Kim bir bilmeceyi ilk çözer?
Kim en fazla emojiyi kullanır?
Kim bir gün belediye başkanı olur?""".splitlines()

# ---------------------------------------------------------------- Çizim Telefonu (starting phrases)
TELEPHONE = """Bisiklete binen bir kedi
Ay'da piknik yapan astronot
Şemsiyeyle uçan bir fil
Dondurma yiyen bir penguen
Gitar çalan bir ahtapot
Denizde yüzen bir ev
Kar tanesinden yapılmış kale
Çay içen bir dinozor
Kaykay yapan bir kaplumbağa
Bulutların üstünde koşan at
Ağaca tırmanan bir balık
Uçan halıda oturan dede
Pasta yiyen bir robot
Kalabalık bir otobüste zürafa
Yağmurda dans eden bir kardan adam
Kitap okuyan bir baykuş
Çölde kayak yapan bir ayı
Roketle okula giden çocuk
Kahve fincanında yüzen bir ördek
Futbol oynayan iki tavuk
Deniz fenerinde yaşayan bir kedi
Kanatları olan bir araba
Pizza dağıtan bir kuş
Uyuyan bir yanardağ
Sirkte ip üstünde yürüyen fil
Gözlüklü bir güneş
Dev bir karpuzun içinde ev
Tren süren bir köpek
Saklambaç oynayan hayaletler
Pazar alışverişi yapan bir uzaylı
Balkonda domates yetiştiren bir kurbağa
Karpuz taşıyan bir karınca
Bulut şeklinde bir koyun
Sörf yapan bir köpek balığı
Kral tacı takan bir fare
Yıldızları toplayan bir çocuk
Plajda kumdan kale yapan bir yengeç
Kütüphanede uyuyan bir aslan
Davul çalan bir tavşan
Halat çeken iki ayı
Merdivenden inen bir kar topu
Dev bir ayakkabıda yaşayan aile
Simit satan bir martı
Kırmızı bir balonla uçan kaplumbağa
Ateş püskürten küçük bir ejderha
Lunaparkta dönme dolap
Aynaya bakan bir maymun
Yatakta kahvaltı eden kedi
Gökkuşağının üstünde kayan çocuk
Mektup taşıyan güvercin
Saçları ağaç olan bir dev
Okyanusun dibinde bir tren
Kraliçe gibi giyinmiş bir tavuk
Bir tencerede banyo yapan ayı
Ay ışığında uluyan bir kurt
Yumurtadan çıkan bir robot
Hamakta uyuyan bir tembel hayvan
Kasabada yürüyen dev bir çilek
Kar küreyen bir penguen
Trafik polisi olan bir zebra""".splitlines()

# ---------------------------------------------------------------- Kelime Zinciri (extra everyday words)
CHAIN = """
ad ağ ağaç ağız ahır akıl akşam alet alın altın anahtar anne araba arı armut arsa aslan asker aşçı at ateş avize ayak ayakkabı ayna ayran ayva
baba bahçe bakkal bal balık balkon bardak barış başak batak bavul bayrak bebek bedel bel belge benzin beyin bez biber bilet bilgi bina bisiklet bitki bodrum boğaz bolluk boya bulut buz buzdolabı
cadde cam ceket cep cetvel ceviz cüzdan çadır çağ çakı çam çanta çatal çatı çay çekiç çene çeşme çiçek çiftçi çikolata çilek çim çizgi çocuk çorap çorba çöl çukur
dağ dal dalga damla davul dede defter deniz dere deri dilek dinozor diş dizi doktor dolap domates dondurma dost duman durak duvar düdük düğme düğün dünya
ekmek ekran el elbise elma emek erik eşek eşya etek ev evrak
fare fasulye fener fidan fil fincan fındık fırın fırça fiyat futbol
gazete gece gelin gemi gezegen göl gölge gömlek göz gözlük gül güneş gürültü
halı hamak hamur harita hasta hava havlu havuç hediye helikopter hırka hikaye horoz hurma
ırmak ışık iğne ilaç inek incir ip irmik iskele istasyon
jeton jüri
kabak kadın kafes kahve kalem kale kalp kamyon kanat kantin kapı kar karpuz kasa kaşık kavun kaya kayık kazak kedi kelebek kemer kemik kertenkele kestane kibrit kilim kiraz kitap koltuk konak kova köpek köprü köy kum kumanda kumru kurabiye kuş kutu kuzu
lahana lamba leke leylek limon lokanta lokum
makas mangal mantar manzara marul masa masal maske maymun mektup menekşe merdiven mevsim meyve mısır mide minder mum musluk mutfak müze müzik
nane nar nehir nesne not
odun ofis okul okyanus olta orman otobüs oyun oyuncak
öğretmen ördek örtü örümcek ötücü özlem
palto pamuk pancar papağan para park pasta patates patika pencere perde peynir pide pilav pipet portakal pul
radyo raf rakam ray reçel renk resim rozet rüya rüzgar
saat sabun saç sahil salata salyangoz sandalye sandık saray sarımsak sebze sepet sırt simit sinek sinema soba sokak su sucuk sumak süt
şapka şeker şehir şelale şemsiye şiir şişe şövalye
tabak tahta tarla tava tavşan tavuk taş taç telefon televizyon tencere tepe terazi terlik tilki top toprak tren tuz tünel türkü
uçak uçurtma umut un uydu uyku üzüm ülke ütü
vagon vapur vazo vida vitrin
yağ yağmur yaka yaprak yastık yatak yayla yazı yelek yelken yemek yıldız yılan yol yorgan yumurta yün yüzük
zaman zambak zarf zeytin zil zürafa
""".split()


def key(s: str) -> str:
    """Mirrors public.dict_key for the characters used here."""
    s = s.replace("I", "ı").replace("İ", "i").lower()
    s = s.translate(str.maketrans("çğıöşüâîû", "cgiosuaiu"))
    s = unicodedata.normalize("NFKD", s)
    s = "".join(ch for ch in s if not unicodedata.combining(ch))
    return re.sub(r"[^a-z0-9]", "", s)


def q(s: str) -> str:
    return "'" + s.replace("'", "''") + "'"


def main():
    rows = []  # (kind, category, payload)
    for cat, words in WORDS.items():
        for w in split_words(words):
            rows.append(("word", cat, {"word": w, "aliases": WORD_ALIASES.get(w, [])}))
    for cat, words in DRAW.items():
        for w in split_words(words):
            rows.append(("draw_word", cat, {"word": w}))
    for cat, items in CHARADES.items():
        for w in items:
            w = "İp atlamak" if w == "Ip atlamak" else w
            rows.append(("charade", cat, {"word": w}))
    for w, taboo in TABOO:
        rows.append(("taboo", "", {"word": w, "taboo": taboo.split()[:5] if " " not in taboo else split_taboo(taboo)}))
    rng = random.Random(20261115)
    for cat, question, right, wrong, expl in QUESTIONS:
        assert len(wrong) == 3 and right not in wrong, question
        opts = [right] + wrong
        rng.shuffle(opts)
        rows.append(("question", cat, {"q": question, "options": opts, "answer": opts.index(right), "explanation": expl}))
    for p in PROMPTS:
        if p.strip():
            rows.append(("prompt", "", {"text": p.strip()}))
    for p in TELEPHONE:
        if p.strip():
            rows.append(("telephone", "", {"text": p.strip()}))

    lexicon = {}
    for w in CHAIN:
        lexicon.setdefault(key(w), w)
    for kind, _, p in rows:
        if kind in ("word", "draw_word", "taboo") and " " not in p["word"]:
            lexicon.setdefault(key(p["word"]), p["word"].lower())

    lines = [
        "-- =====================================================================",
        "-- LOOPA 0.5.0 — content pools of the 9 games (generated by tools/modes/build_content.py)",
        "-- Written for LOOPA (CC0). Idempotent: existing rows are kept.",
        "-- =====================================================================",
        "insert into public.mode_content(kind, category, payload) values",
        ",\n".join(f"  ({q(k)}, {q(c)}, {q(json.dumps(p, ensure_ascii=False))}::jsonb)" for k, c, p in rows),
        "on conflict do nothing;",
        "",
        "insert into public.chain_lexicon(match_key, word) values",
        ",\n".join(f"  ({q(k)}, {q(w)})" for k, w in sorted(lexicon.items()) if len(k) >= 2),
        "on conflict (match_key) do nothing;",
        "",
    ]
    OUT.write_text("\n".join(lines), encoding="utf-8")
    counts = {}
    for k, _, _ in rows:
        counts[k] = counts.get(k, 0) + 1
    print(OUT.name, counts, "lexicon", len(lexicon))


def split_words(s: str):
    """Words are separated by spaces; known two-word items are kept together."""
    two = ["Çamaşır makinesi", "Kar tanesi", "Ay tutulması", "Patlamış mısır", "Mercimek çorbası", "Masa tenisi", "Doğum günü",
           "Havai fişek", "Deniz feneri", "Uzay mekiği"]
    out, rest = [], s
    for t in two:
        if t in rest:
            out.append(t)
            rest = rest.replace(t, " ")
    out += rest.split()
    return out


def split_taboo(s: str):
    two = ["kardan adam", "patlamış mısır", "el öpmek", "doğum günü", "dönme dolap"]
    out, rest = [], s
    for t in two:
        if t in rest:
            out.append(t)
            rest = rest.replace(t, " ")
    out += rest.split()
    assert len(out) == 5, (s, out)
    return out


if __name__ == "__main__":
    main()
