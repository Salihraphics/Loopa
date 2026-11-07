-- =====================================================================
-- LOOPA — catalogue seed: categories and cosmetic items.
-- Cosmetic ids must match com.loopa.core.ui.avatar.AvatarCatalog.
-- =====================================================================

insert into public.categories(key, name_tr, name_en, is_default, sort_order) values
    ('isim',   'İsim',   'Name',       true,  1),
    ('sehir',  'Şehir',  'City',       true,  2),
    ('hayvan', 'Hayvan', 'Animal',     true,  3),
    ('bitki',  'Bitki',  'Plant',      true,  4),
    ('esya',   'Eşya',   'Object',     true,  5),
    ('ulke',   'Ülke',   'Country',    true,  6),
    ('meslek', 'Meslek', 'Profession', true,  7),
    ('unlu',   'Ünlü',   'Celebrity',  true,  8),
    ('marka',  'Marka',  'Brand',      false, 9),
    ('yemek',  'Yemek',  'Food',       false, 10),
    ('film',   'Film',   'Movie',      false, 11),
    ('oyun',   'Oyun',   'Game',       false, 12)
on conflict (key) do update set name_tr = excluded.name_tr, name_en = excluded.name_en,
    is_default = excluded.is_default, sort_order = excluded.sort_order;

insert into public.cosmetic_items(id, slot, name_tr, required_level, sort_order) values
    -- head shapes
    ('base_round',  'base', 'Yuvarlak', 1, 1),
    ('base_oval',   'base', 'Oval',     1, 2),
    ('base_square', 'base', 'Köşeli',   1, 3),
    ('base_bean',   'base', 'Fasulye',  3, 4),
    -- skin / character colors
    ('skin_1', 'skin', 'Açık',      1, 1),
    ('skin_2', 'skin', 'Buğday',    1, 2),
    ('skin_3', 'skin', 'Bal',       1, 3),
    ('skin_4', 'skin', 'Kahve',     1, 4),
    ('skin_5', 'skin', 'Koyu',      1, 5),
    ('skin_6', 'skin', 'Şeftali',   1, 6),
    ('skin_mint',     'skin', 'Nane Uzaylı',  6, 7),
    ('skin_lavender', 'skin', 'Lavanta Peri', 10, 8),
    -- hair styles
    ('hair_short',    'hair', 'Kısa',       1, 1),
    ('hair_curly',    'hair', 'Kıvırcık',   1, 2),
    ('hair_long',     'hair', 'Uzun',       1, 3),
    ('hair_bob',      'hair', 'Kaküllü',    1, 4),
    ('hair_bun',      'hair', 'Topuz',      2, 5),
    ('hair_spiky',    'hair', 'Diken',      3, 6),
    ('hair_ponytail', 'hair', 'At Kuyruğu', 4, 7),
    ('hair_afro',     'hair', 'Afro',       5, 8),
    ('hair_mohawk',   'hair', 'Mohikan',    8, 9),
    -- hair colors
    ('hc_black',    'hair_color', 'Siyah',   1, 1),
    ('hc_brown',    'hair_color', 'Kahve',   1, 2),
    ('hc_blonde',   'hair_color', 'Sarı',    1, 3),
    ('hc_ginger',   'hair_color', 'Kızıl',   1, 4),
    ('hc_gray',     'hair_color', 'Gri',     2, 5),
    ('hc_pink',     'hair_color', 'Pembe',   4, 6),
    ('hc_mint',     'hair_color', 'Nane',    6, 7),
    ('hc_lavender', 'hair_color', 'Lavanta', 9, 8),
    -- eyes
    ('eyes_dot',    'eyes', 'Nokta',    1, 1),
    ('eyes_round',  'eyes', 'Yuvarlak', 1, 2),
    ('eyes_happy',  'eyes', 'Mutlu',    1, 3),
    ('eyes_wink',   'eyes', 'Göz Kırp', 2, 4),
    ('eyes_sleepy', 'eyes', 'Uykulu',   3, 5),
    ('eyes_star',   'eyes', 'Yıldız',   7, 6),
    ('eyes_heart',  'eyes', 'Kalp',     12, 7),
    -- mouth / expressions
    ('mouth_smile',  'mouth', 'Gülümseme', 1, 1),
    ('mouth_grin',   'mouth', 'Sırıtış',   1, 2),
    ('mouth_open',   'mouth', 'Şaşkın',    1, 3),
    ('mouth_flat',   'mouth', 'Ciddi',     1, 4),
    ('mouth_tongue', 'mouth', 'Dil',       2, 5),
    ('mouth_cat',    'mouth', 'Kedi',      5, 6),
    -- glasses
    ('glasses_round',   'glasses', 'Yuvarlak Gözlük', 1, 1),
    ('glasses_square',  'glasses', 'Kare Gözlük',     2, 2),
    ('glasses_sun',     'glasses', 'Güneş Gözlüğü',   4, 3),
    ('glasses_star',    'glasses', 'Yıldız Gözlük',   9, 4),
    ('glasses_monocle', 'glasses', 'Monokl',          14, 5),
    -- hats
    ('hat_cap',        'hat', 'Şapka',      1, 1),
    ('hat_beanie',     'hat', 'Bere',       2, 2),
    ('hat_party',      'hat', 'Parti',      3, 3),
    ('hat_bucket',     'hat', 'Balıkçı',    5, 4),
    ('hat_headphones', 'hat', 'Kulaklık',   7, 5),
    ('hat_flower',     'hat', 'Çiçek Taç',  10, 6),
    ('hat_wizard',     'hat', 'Büyücü',     13, 7),
    ('hat_crown',      'hat', 'Taç',        18, 8),
    -- outfits
    ('outfit_tshirt',  'outfit', 'Tişört',    1, 1),
    ('outfit_hoodie',  'outfit', 'Kapüşonlu', 1, 2),
    ('outfit_stripes', 'outfit', 'Çizgili',   2, 3),
    ('outfit_sweater', 'outfit', 'Kazak',     3, 4),
    ('outfit_overall', 'outfit', 'Tulum',     5, 5),
    ('outfit_jersey',  'outfit', 'Forma',     7, 6),
    ('outfit_suit',    'outfit', 'Takım',     11, 7),
    ('outfit_cape',    'outfit', 'Pelerin',   16, 8),
    -- accessories
    ('acc_blush',    'accessory', 'Allık',    1, 1),
    ('acc_freckles', 'accessory', 'Çil',      1, 2),
    ('acc_earrings', 'accessory', 'Küpe',     2, 3),
    ('acc_bowtie',   'accessory', 'Papyon',   4, 4),
    ('acc_scarf',    'accessory', 'Atkı',     6, 5),
    ('acc_bandaid',  'accessory', 'Yara Bandı', 8, 6),
    ('acc_necklace', 'accessory', 'Kolye',    12, 7),
    -- backgrounds
    ('bg_lavender', 'background', 'Lavanta', 1, 1),
    ('bg_mint',     'background', 'Nane',    1, 2),
    ('bg_peach',    'background', 'Şeftali', 1, 3),
    ('bg_butter',   'background', 'Tereyağı', 1, 4),
    ('bg_sky',      'background', 'Gökyüzü', 1, 5),
    ('bg_dots',     'background', 'Puantiye', 3, 6),
    ('bg_stripes',  'background', 'Çizgili', 5, 7),
    ('bg_stars',    'background', 'Yıldızlı', 8, 8),
    ('bg_sunburst', 'background', 'Güneş',   15, 9),
    -- profile frames
    ('frame_mint',    'frame', 'Nane Çerçeve',     2, 1),
    ('frame_loopa',   'frame', 'LOOPA Çerçeve',    5, 2),
    ('frame_gold',    'frame', 'Altın Çerçeve',    10, 3),
    ('frame_rainbow', 'frame', 'Gökkuşağı Çerçeve', 20, 4),
    -- decorations
    ('deco_sparkles',  'decoration', 'Pırıltı',   4, 1),
    ('deco_hearts',    'decoration', 'Kalpler',   9, 2),
    ('deco_lightning', 'decoration', 'Şimşek',    14, 3),
    ('deco_halo',      'decoration', 'Hale',      25, 4)
on conflict (id) do update set slot = excluded.slot, name_tr = excluded.name_tr,
    required_level = excluded.required_level, sort_order = excluded.sort_order;
