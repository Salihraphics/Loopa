-- =====================================================================
-- LOOPA — Turkish validation dictionary (deterministic, no AI calls).
-- Words found here are auto-accepted. Words that start with the right
-- letter but are not listed become "uncertain" and go to a player vote.
-- Extend freely: words are normalized on insert.
-- =====================================================================

create or replace function public._seed_words(p_category text, p_words text[])
returns void language sql as $$
    insert into public.word_dictionary(category_key, word)
    select p_category, public.normalize_answer(w) from unnest(p_words) w
    where public.normalize_answer(w) <> ''
    on conflict do nothing;
$$;

select public._seed_words('isim', array[
 'Ahmet','Ali','Ayşe','Aylin','Ayla','Aslı','Arda','Alper','Asya','Ada','Ahu','Aras','Aydın','Atakan','Alp','Arzu','Asena','Ayten','Aziz','Azra',
 'Barış','Burak','Berk','Buse','Burcu','Banu','Bora','Bahar','Batuhan','Berkay','Begüm','Betül','Bülent','Büşra','Bilge','Berna','Beren','Baran',
 'Can','Cem','Ceren','Cansu','Cenk','Ceyda','Canan','Cihan','Cemal','Cengiz','Cemre','Cüneyt',
 'Çağla','Çağan','Çağrı','Çiğdem','Çınar','Çisem','Çetin','Çiçek',
 'Deniz','Derya','Doruk','Duygu','Defne','Demet','Doğan','Dilek','Dilan','Damla','Dora','Duru','Dursun','Davut',
 'Ece','Elif','Emre','Esra','Eda','Ebru','Efe','Ege','Emel','Engin','Erkan','Ertuğrul','Eylül','Ezgi','Evren','Esin','Erdem','Elçin','Emir','Enes',
 'Fatma','Fatih','Ferhat','Fulya','Funda','Feyza','Furkan','Filiz','Ferit','Fadime','Fikret','Fırat',
 'Gamze','Gizem','Gökhan','Gül','Gülşen','Gökay','Gonca','Güneş','Görkem','Gaye','Gülay','Gürkan','Güler',
 'Hakan','Hande','Hasan','Hüseyin','Hülya','Halil','Hazal','Hilal','Harun','Hatice','Hayri','Hümeyra',
 'Irmak','Işık','Işıl','Ilgaz','Ilgın',
 'İbrahim','İlker','İpek','İrem','İsmail','İlayda','İsmet','İlhan','İnci','İskender','İlknur','İdil',
 'Jale','Jülide','Janset',
 'Kaan','Kerem','Kemal','Kübra','Kader','Koray','Kenan','Kevser','Kumsal','Kutay','Kardelen',
 'Leyla','Levent','Lale','Lütfi','Lara','Lerzan',
 'Mehmet','Mustafa','Merve','Melis','Melek','Murat','Mert','Meltem','Mine','Mahmut','Melike','Metin','Miray','Mira','Mehtap','Mustafa',
 'Nazlı','Nur','Nehir','Necati','Nihat','Nilüfer','Nesrin','Nil','Nisa','Nail','Neslihan','Nazım',
 'Oğuz','Orhan','Okan','Onur','Oya','Oktay','Ozan','Osman','Orkun',
 'Ömer','Özge','Özlem','Özgür','Ömür','Öykü','Özden','Öznur',
 'Pelin','Pınar','Poyraz','Perihan','Polat',
 'Rabia','Recep','Rüya','Reşat','Rıza','Rana','Rukiye','Ramazan',
 'Selin','Sinan','Sibel','Serkan','Seda','Sevgi','Su','Simge','Sena','Selim','Sude','Sertaç','Sevda','Sezen','Saadet','Sami',
 'Şule','Şeyma','Şebnem','Şahin','Şükrü','Şirin','Şenol','Şevval',
 'Tolga','Tuğba','Tarık','Tuna','Tülay','Tuba','Taylan','Toprak','Tamer','Tuncay','Tuğçe',
 'Umut','Uğur','Utku','Ufuk','Ulaş','Umay',
 'Ümit','Ülkü','Ünal','Ümran',
 'Volkan','Vildan','Veli','Vural','Vahit',
 'Yasemin','Yusuf','Yavuz','Yağmur','Yiğit','Yıldız','Yasin','Yeliz','Yaren','Yunus','Yonca',
 'Zeynep','Zehra','Zafer','Zerrin','Ziya','Zuhal','Zekai','Zümra'
]);

select public._seed_words('sehir', array[
 'Adana','Adıyaman','Afyon','Afyonkarahisar','Ağrı','Aksaray','Amasya','Ankara','Antalya','Ardahan','Artvin','Aydın','Balıkesir','Bartın','Batman',
 'Bayburt','Bilecik','Bingöl','Bitlis','Bolu','Burdur','Bursa','Çanakkale','Çankırı','Çorum','Denizli','Diyarbakır','Düzce','Edirne','Elazığ',
 'Erzincan','Erzurum','Eskişehir','Gaziantep','Antep','Giresun','Gümüşhane','Hakkari','Hatay','Iğdır','Isparta','İstanbul','İzmir','Kahramanmaraş','Maraş',
 'Karabük','Karaman','Kars','Kastamonu','Kayseri','Kırıkkale','Kırklareli','Kırşehir','Kilis','Kocaeli','İzmit','Konya','Kütahya','Malatya','Manisa',
 'Mardin','Mersin','İçel','Muğla','Muş','Nevşehir','Niğde','Ordu','Osmaniye','Rize','Sakarya','Adapazarı','Samsun','Siirt','Sinop','Sivas','Şanlıurfa','Urfa',
 'Şırnak','Tekirdağ','Tokat','Trabzon','Tunceli','Uşak','Van','Yalova','Yozgat','Zonguldak',
 'Alanya','Bodrum','Fethiye','Marmaris','Kuşadası','Çeşme','Ayvalık','İskenderun','Ereğli','Tarsus','Bandırma','Didim','Kapadokya','Safranbolu',
 'Amsterdam','Atina','Bakü','Barselona','Berlin','Bern','Brüksel','Budapeşte','Bükreş','Kahire','Dubai','Dublin','Floransa','Frankfurt','Hamburg',
 'Helsinki','Kopenhag','Lizbon','Londra','Los Angeles','Lyon','Madrid','Marsilya','Milano','Moskova','Münih','Napoli','New York','Oslo','Paris',
 'Prag','Riga','Roma','Saraybosna','Selanik','Seul','Sidney','Singapur','Sofya','Stockholm','Tahran','Tiflis','Tokyo','Toronto','Üsküp','Varşova',
 'Venedik','Viyana','Washington','Zürih','Pekin','Şangay','Bangkok','Delhi','Mumbai','İslamabad','Bağdat','Şam','Beyrut','Doha','Riyad','Mekke',
 'Medine','Kudüs','Taşkent','Semerkant','Buhara','Astana','Almatı','Bişkek','Aşkabat','Erivan','Kiev','Odessa','Minsk','Vilnius','Tallinn',
 'Zagreb','Belgrad','Tiran','Lefkoşa','Girne','Gazimağusa','Venedik','Viyana','Rio','Rio de Janeiro','Buenos Aires','Lima','Bogota','Havana',
 'Meksiko','Chicago','Boston','Miami','Vancouver','Montreal','Melbourne','Kazan','Nahçıvan','Batum','Üsküp','Prizren','Mostar','Ohri'
]);

select public._seed_words('ulke', array[
 'Afganistan','Almanya','Amerika','ABD','Amerika Birleşik Devletleri','Andorra','Angola','Arjantin','Arnavutluk','Avustralya','Avusturya','Azerbaycan',
 'Bahreyn','Bangladeş','Belarus','Beyaz Rusya','Belçika','Benin','Bolivya','Bosna Hersek','Botsvana','Brezilya','Bulgaristan','Burkina Faso','Butan',
 'Cezayir','Cibuti','Çad','Çekya','Çek Cumhuriyeti','Çin','Danimarka','Dominik','Ekvador','Endonezya','Eritre','Ermenistan','Estonya','Etiyopya',
 'Fas','Fiji','Fildişi Sahili','Filipinler','Filistin','Finlandiya','Fransa','Gabon','Gambiya','Gana','Gine','Grönland','Guatemala','Gürcistan',
 'Güney Afrika','Güney Kore','Haiti','Hırvatistan','Hindistan','Hollanda','Honduras','Irak','İngiltere','Birleşik Krallık','İran','İrlanda','İspanya',
 'İsrail','İsveç','İsviçre','İtalya','İzlanda','Jamaika','Japonya','Kamboçya','Kamerun','Kanada','Karadağ','Katar','Kazakistan','Kenya','Kırgızistan',
 'Kıbrıs','KKTC','Kolombiya','Kongo','Kore','Kosova','Kostarika','Kuveyt','Kuzey Kore','Kuzey Makedonya','Makedonya','Küba','Laos','Letonya','Liberya',
 'Libya','Lihtenştayn','Litvanya','Lübnan','Lüksemburg','Macaristan','Madagaskar','Malavi','Maldivler','Malezya','Mali','Malta','Meksika','Mısır',
 'Moğolistan','Moldova','Monako','Moritanya','Mozambik','Myanmar','Namibya','Nepal','Nijer','Nijerya','Nikaragua','Norveç','Özbekistan','Pakistan',
 'Panama','Paraguay','Peru','Polonya','Portekiz','Romanya','Ruanda','Rusya','San Marino','Senegal','Sırbistan','Singapur','Slovakya','Slovenya',
 'Somali','Sri Lanka','Sudan','Suriye','Suudi Arabistan','Şili','Tacikistan','Tanzanya','Tayland','Tayvan','Togo','Tonga','Tunus','Türkiye',
 'Türkmenistan','Uganda','Ukrayna','Umman','Uruguay','Ürdün','Vatikan','Venezuela','Vietnam','Yemen','Yeni Zelanda','Yunanistan','Zambiya','Zimbabve',
 'Birleşik Arap Emirlikleri','BAE','Bahamalar','Barbados','Belize','Brunei','Burundi','Komorlar','Lesoto','Mauritius','Monako','Nauru','Palau',
 'Samoa','Seyşeller','Surinam','Svaziland','Esvatini','Tuvalu','Vanuatu','Ekvator Ginesi','Gine Bissau','Sierra Leone','Zaire','Hollanda'
]);

select public._seed_words('hayvan', array[
 'Aslan','At','Arı','Ayı','Akrep','Ahtapot','Antilop','Alabalık','Akbaba','Ağustos Böceği','Atmaca','Armadillo','Aygır',
 'Balık','Baykuş','Bukalemun','Bıldırcın','Bit','Böcek','Boğa','Balina','Bizon','Bülbül','Barbunya','Bufalo','Babun',
 'Ceylan','Civciv','Cırcır Böceği','Çakal','Çekirge','Çita','Çıyan','Çipura','Çalıkuşu',
 'Deve','Domuz','Dana','Deniz Anası','Denizatı','Dinozor','Doğan','Dağ Keçisi','Deve Kuşu','Dingo',
 'Eşek','Ejderha','Engerek','Ergin','Eşekarısı','Emu',
 'Fil','Fare','Flamingo','Fok','Firavun Faresi','Fino',
 'Geyik','Gergedan','Goril','Güvercin','Gelincik','Guguk Kuşu','Güve','Göçmen Kuş','Gazel',
 'Hamster','Horoz','Hindi','Hipopotam','Hamsi','Hörgüç','Hamamböceği','Hüthüt',
 'Istakoz','Isırgan',
 'İnek','İguana','İpek Böceği','İstavrit','İbibik','İmpala',
 'Jaguar','Jako','Jöle Balığı',
 'Kedi','Köpek','Kurt','Kartal','Kaplan','Karınca','Kaplumbağa','Kanguru','Koala','Kirpi','Kertenkele','Koyun','Keçi','Kelebek','Karga','Kuğu',
 'Kunduz','Kobra','Kurbağa','Kalkan','Kılıç Balığı','Kirpi','Koç','Köpekbalığı','Karakulak','Kanarya','Keklik',
 'Leopar','Leylek','Lama','Levrek','Lemur',
 'Maymun','Martı','Midye','Mandril','Manda','Mürekkep Balığı','Mors','Muhabbet Kuşu','Mamut',
 'Nil Timsahı','Nalbant','Nergis Balığı',
 'Orangutan','Orkinos','Okapi','Oğlak','Ördek','Örümcek','Öküz','Ötleğen',
 'Papağan','Panda','Penguen','Pelikan','Puma','Pire','Palamut','Panter','Piton','Pisi',
 'Rakun','Ren Geyiği',
 'Sincap','Salyangoz','Sinek','Sivrisinek','Serçe','Sazan','Sardalya','Sığır','Su Aygırı','Sansar','Somon','Sırtlan','Solucan','Samur',
 'Şahin','Şempanze','Şebek','Şahmeran',
 'Tavşan','Tavuk','Tilki','Timsah','Tavus Kuşu','Tırtıl','Toy','Turna','Ton Balığı','Tarantula','Tukan','Tazı',
 'Uğur Böceği','Uskumru','Uğurböceği',
 'Üveyik','Üveyik Kuşu',
 'Vaşak','Vatoz','Vizon',
 'Yılan','Yunus','Yarasa','Yengeç','Yak','Yaban Domuzu','Yusufçuk','Yaban Arısı',
 'Zebra','Zürafa','Zargana','Zerdeva'
]);

select public._seed_words('bitki', array[
 'Ayçiçeği','Ağaç','Akasya','Ardıç','Ayva','Armut','Asma','Arpa','Ahududu','Avokado','Anason','Ananas','Aloe Vera','Ağaç Mantarı',
 'Begonya','Biber','Böğürtlen','Buğday','Badem','Bambu','Biberiye','Bezelye','Brokoli','Begonvil','Bakla',
 'Ceviz','Civanperçemi','Camgüzeli','Cevizağacı',
 'Çam','Çınar','Çiçek','Çilek','Çay','Çuha Çiçeği','Çörek Otu','Çiğdem',
 'Dut','Defne','Dereotu','Domates','Dağ Çileği','Dişbudak',
 'Elma','Erik','Ebegümeci','Enginar','Eğrelti Otu','Ekinezya',
 'Fesleğen','Fındık','Fasulye','Frezya','Fıstık','Fıstık Çamı','Fulya',
 'Gül','Greyfurt','Gelincik','Ginseng','Glayöl','Günebakan','Gürgen',
 'Hanımeli','Hurma','Havuç','Hardal','Haşhaş','Hindiba','Hatmi',
 'Ihlamur','Isırgan','Isırgan Otu','Ispanak','Ilgın',
 'İncir','İğde','İris','İstiridye Mantarı','İnci Çiçeği',
 'Japon Gülü','Japon Kirazı','Jasmin',
 'Kavak','Kaktüs','Karanfil','Kiraz','Kayısı','Kestane','Kardelen','Kekik','Kavun','Karpuz','Kızılcık','Kamelya','Kadife Çiçeği','Karnabahar','Kereviz','Kivi',
 'Lale','Leylak','Limon','Lavanta','Lahana','Lotus','Lilyum','Ladin',
 'Menekşe','Mantar','Manolya','Mısır','Mandalina','Muz','Maydanoz','Mimoza','Mersin','Mercimek','Melisa','Mango',
 'Nane','Nergis','Nar','Nilüfer','Nohut',
 'Orkide','Ormangülü','Okaliptüs','Oğulotu','Ortanca',
 'Ökse Otu','Ölmez Çiçek',
 'Papatya','Pamuk','Patates','Pırasa','Portakal','Palmiye','Pelin Otu','Petunya','Patlıcan',
 'Reyhan','Roka','Rezene',
 'Sardunya','Söğüt','Sarmaşık','Sümbül','Sarımsak','Soğan','Semizotu','Salatalık','Sedir','Selvi',
 'Şebboy','Şeftali','Şakayık','Şimşir','Şalgam',
 'Turp','Tütün','Tere','Turunç','Tarçın','Tırmalayan',
 'Üzüm','Üvez',
 'Vişne','Vanilya',
 'Yasemin','Yonca','Yulaf','Yosun','Yaban Mersini','Yer Fıstığı',
 'Zambak','Zeytin','Zencefil','Zerdeçal','Zakkum','Zeytin Ağacı'
]);

select public._seed_words('esya', array[
 'Ayna','Anahtar','Ayakkabı','Abajur','Ataş','Askı','Araba','Avize','Ajanda','Akvaryum','Atkı','Ataç',
 'Bardak','Bilgisayar','Bıçak','Buzdolabı','Battaniye','Bavul','Bisiklet','Boya','Bant','Balon','Bilezik','Baston','Bere',
 'Cetvel','Cüzdan','Ceket','Cam','Cezve','Canta',
 'Çanta','Çatal','Çaydanlık','Çekiç','Çorap','Çivi','Çakmak','Çerçeve','Çadır','Çamaşır Makinesi',
 'Dolap','Defter','Dürbün','Davul','Dikiş Makinesi','Duvar Saati','Demlik',
 'Eldiven','Etek','Elbise','Ekmek Bıçağı','Eşarp',
 'Fırça','Fincan','Fırın','Fener','Fotoğraf Makinesi','Futbol Topu','Fön Makinesi',
 'Gözlük','Gömlek','Gitar','Gardırop',
 'Halı','Havlu','Hırka','Hoparlör','Heykel','Hamak',
 'Iskarta','Izgara','Ispatula',
 'İğne','İp','İskemle','İstiridye Bıçağı',
 'Jilet','Jeneratör','Jöle',
 'Kalem','Kitap','Kaşık','Koltuk','Kapı','Kemer','Kumanda','Kupa','Klavye','Kulaklık','Kravat','Küpe','Kase','Kazak','Kibrit','Kasa','Kilit','Kamera',
 'Lamba','Leğen','Lastik','Lavabo',
 'Masa','Makas','Mum','Mikrofon','Mouse','Musluk','Mendil','Matkap','Merdiven','Mikser',
 'Not Defteri','Nalbur','Nargile',
 'Oklava','Oyuncak','Ocak',
 'Örtü','Önlük',
 'Pantolon','Perde','Priz','Pil','Piyano','Poşet','Pense','Pijama','Pipet',
 'Radyo','Raket','Ruj',
 'Sandalye','Saat','Silgi','Süpürge','Sehpa','Sabun','Sürahi','Semaver','Sünger','Sepet','Şemsiye',
 'Şişe','Şapka','Şamdan','Şarj Aleti','Şezlong',
 'Tabak','Tencere','Televizyon','Tava','Tarak','Telefon','Tornavida','Terlik','Top','Tablet','Teyp',
 'Usb','Ütü','Ütü Masası',
 'Vazo','Valiz','Vantilatör',
 'Yastık','Yorgan','Yüzük','Yatak','Yelpaze',
 'Zil','Zımba','Zarf','Zar','Zincir'
]);

select public._seed_words('meslek', array[
 'Avukat','Aşçı','Astronot','Aktör','Asker','Arkeolog','Antrenör','Ayakkabıcı','Ambulans Şoförü','Akademisyen','Astronom','Ahçı','Ajan','Animatör',
 'Berber','Bahçıvan','Bankacı','Bakkal','Balıkçı','Bilim İnsanı','Biyolog','Barmen','Besteci','Bekçi',
 'Cerrah','Cankurtaran','Camcı',
 'Çiftçi','Çoban','Çevirmen','Çilingir','Çaycı','Çöpçü',
 'Doktor','Diş Hekimi','Diyetisyen','Dalgıç','Dansçı','Davulcu','Dekoratör','Demirci','Dişçi',
 'Eczacı','Elektrikçi','Editör','Ebe','Esnaf','Emlakçı',
 'Fotoğrafçı','Fırıncı','Futbolcu','Fizikçi','Fizyoterapist','Fabrikatör',
 'Gazeteci','Garson','Gemici','Grafiker','Güvenlik Görevlisi','Gözlükçü','Gümrükçü',
 'Hemşire','Hakim','Hakem','Hostes','Heykeltıraş','Hamal','Hafız','Haritacı',
 'Itfaiyeci','Işıkçı',
 'İtfaiyeci','İnşaat Mühendisi','İmam','İşçi','İllüzyonist','İstatistikçi',
 'Jokey','Jeolog','Jandarma','Jimnastikçi',
 'Kasap','Kaptan','Kuaför','Kimyager','Kameraman','Kasiyer','Kuyumcu','Kurye','Kaportacı','Kütüphaneci','Komedyen','Kalaycı',
 'Lokantacı','Laborant',
 'Mühendis','Muhasebeci','Manav','Marangoz','Mimar','Müzisyen','Matematikçi','Model','Madenci','Makinist','Manken','Muhtar',
 'Noter','Nalbant','Nakliyeci',
 'Öğretmen','Oyuncu','Operatör','Ormancı','Oto Tamircisi',
 'Pilot','Polis','Psikolog','Postacı','Programcı','Pazarlamacı','Pastacı','Profesör','Psikiyatrist',
 'Ressam','Rehber','Rehberlik Öğretmeni','Radyolog','Reklamcı',
 'Sekreter','Satıcı','Savcı','Şoför','Simitçi','Sunucu','Senarist','Spiker','Sporcu','Stilist',
 'Şarkıcı','Şair','Şef','Şekerci',
 'Terzi','Tesisatçı','Tamirci','Tercüman','Teknisyen','Tasarımcı','Tarihçi','Tiyatrocu','Turist Rehberi',
 'Uzay Mühendisi','Uçuş Görevlisi',
 'Ütücü','Ürolog',
 'Veteriner','Vali','Vatman','Vergi Müfettişi',
 'Yazar','Yazılımcı','Yönetmen','Yüzücü','Yargıç','Yapımcı',
 'Zabıta','Ziraatçi','Zanaatkar','Ziraat Mühendisi'
]);

select public._seed_words('unlu', array[
 'Atatürk','Aleyna Tilki','Ajda Pekkan','Acun Ilıcalı','Ali Koç','Arda Turan','Aziz Sancar','Albert Einstein',
 'Barış Manço','Beyazıt Öztürk','Burak Özçivit','Bergüzar Korel','Beyoncé','Brad Pitt',
 'Cem Yılmaz','Cristiano Ronaldo','Ceza','Candan Erçetin','Cüneyt Arkın',
 'Demet Akalın','Demet Evgar',
 'Ebru Gündeş','Edis','Engin Akyürek','Ezel','Elon Musk','Ece Seçkin',
 'Fatih Terim','Fazıl Say','Fatma Girik',
 'Gökhan Özoğuz','Gülşen','Gökhan Türkmen',
 'Hadise','Hande Yener','Hülya Avşar','Halit Ergenç','Haluk Bilginer',
 'İbrahim Tatlıses','İlber Ortaylı','İbrahim Erkal',
 'Kenan Doğulu','Kemal Sunal','Kıvanç Tatlıtuğ','Kenan İmirzalıoğlu',
 'Lionel Messi',
 'Müslüm Gürses','Mabel Matiz','Michael Jackson','Mazhar Alanson','Mert Fırat','Murat Boz',
 'Nazım Hikmet','Nil Karaibrahimgil','Neşet Ertaş',
 'Orhan Pamuk','Orhan Gencebay','Ozan Güven',
 'Özcan Deniz','Ömer Seyfettin',
 'Pamela Spence',
 'Rihanna','Rafael Nadal',
 'Sezen Aksu','Serdar Ortaç','Sertab Erener','Selda Bağcan','Şener Şen','Şebnem Ferah','Şahan Gökbakar',
 'Tarkan','Türkan Şoray','Tuba Büyüküstün','Taylor Swift',
 'Uğur Yücel',
 'Yılmaz Erdoğan','Yıldız Tilbe','Yaşar Kemal',
 'Zeki Müren','Zeynep Bastık','Zinedine Zidane'
]);

select public._seed_words('marka', array[
 'Adidas','Apple','Arçelik','Aselsan','Audi','Algida','Amazon','Asus',
 'Beko','BMW','Bosch','Burger King','Bim',
 'Coca Cola','Canon','Converse','Casio',
 'Çaykur','Çokokrem',
 'Dell','Disney','Dacia',
 'Eti','Ericsson',
 'Ford','Fiat','Ferrari','Facebook',
 'Google','Gucci','Garanti',
 'Honda','HP','Huawei','Hyundai',
 'Instagram','Intel','IKEA',
 'Jaguar','Jeep',
 'Kia','KFC','Koton',
 'Lego','Lenovo','LG','Lacoste','Levis',
 'Mercedes','Microsoft','Migros','McDonalds','Mavi','Mazda',
 'Nike','Nokia','Nestle','Netflix','Nintendo',
 'Opel','Oppo',
 'Philips','Pepsi','Puma','Peugeot','Pınar','Porsche',
 'Renault','Rolex',
 'Samsung','Sony','Starbucks','Sütaş','Skoda','Spotify',
 'Toyota','Tesla','THY','Türk Telekom','Turkcell','Togg',
 'Ülker','Vestel','Volkswagen','Volvo','Vodafone',
 'Xiaomi','Yamaha','Youtube','Zara'
]);

select public._seed_words('yemek', array[
 'Adana Kebap','Ayran Aşı','Aşure','Ali Nazik','Arnavut Ciğeri',
 'Baklava','Börek','Biber Dolması','Beyti','Bulgur Pilavı','Bamya',
 'Cacık','Cağ Kebabı','Ciğer',
 'Çorba','Çiğ Köfte','Çökertme','Çılbır',
 'Döner','Dolma','Dürüm','Dondurma',
 'Ezogelin','Etli Ekmek','Ekmek Kadayıfı','Erişte',
 'Fasulye','Fıstıklı Kebap','Falafel',
 'Güveç','Gözleme','Gullaç',
 'Hamsi Tava','Hünkar Beğendi','Helva','Humus','Hamburger',
 'Islama Köfte','Islak Hamburger','Irmik Helvası',
 'İskender','İmam Bayıldı','İçli Köfte','İrmik Tatlısı',
 'Kebap','Köfte','Kuru Fasulye','Karnıyarık','Kumpir','Kadayıf','Künefe','Kokoreç','Kısır','Keşkek','Kavurma',
 'Lahmacun','Lahana Sarması','Lokma','Lazanya',
 'Mantı','Menemen','Mercimek Çorbası','Musakka','Midye Dolma','Makarna','Mücver',
 'Nohut','Nuga',
 'Omlet','Orman Kebabı',
 'Pide','Pilav','Pizza','Patates Kızartması','Paça Çorbası','Profiterol',
 'Revani','Ravioli',
 'Sarma','Simit','Sütlaç','Su Böreği','Sucuk','Şiş Kebap','Şakşuka','Şöbiyet',
 'Tantuni','Tarhana','Tavuk Göğsü','Taze Fasulye','Tulumba',
 'Urfa Kebap','Un Helvası',
 'Ütü Tatlısı',
 'Vişneli Ekmek',
 'Yaprak Sarma','Yayla Çorbası','Yoğurtlu Kebap',
 'Zeytinyağlı','Zerde'
]);

select public._seed_words('film', array[
 'Avatar','Aslan Kral','Arog','Ayla','Babam ve Oğlum','Bir Zamanlar Anadolu’da','Batman','Babil',
 'Çakallarla Dans','Dünyayı Kurtaran Adam','Eşkıya','Ekümenopolis','Forrest Gump','Gora','Gladyatör','Hababam Sınıfı',
 'Harry Potter','Inception','İnterstellar','Joker','Kış Uykusu','Kelebeğin Rüyası','Leon','Matrix','Mustang','Nefes',
 'Organize İşler','Papillon','Recep İvedik','Selam','Titanik','Toy Story','Up','Vizontele','Yüzüklerin Efendisi','Zindan'
]);

select public._seed_words('oyun', array[
 'Amiral Battı','Ayakkabı Bağlama','Bilardo','Briç','Bowling','Basketbol','Çelik Çomak','Dama','Domino','Dart',
 'Futbol','Golf','Hentbol','İsim Şehir','İstop','Jenga','Körebe','Kutu Oyunu','Kızma Birader','Labirent','Monopoly','Mangala',
 'Okey','Pişti','Poker','Rubik Küp','Saklambaç','Satranç','Sudoku','Tavla','Tabu','Tetris','Uno','Voleybol','Yakan Top','Yazı Tura','Zıp Zıp'
]);

drop function public._seed_words(text, text[]);
