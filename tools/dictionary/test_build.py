"""Checks that the Python normalisation used by build.py mirrors the SQL functions."""
import os, sys, unittest
sys.path.insert(0, os.path.dirname(__file__))
import build

class Mirror(unittest.TestCase):
    def test_keys(self):
        self.assertEqual(build.key("Çiğ Köfte"), "cigkofte")
        self.assertEqual(build.key("  İSTANBUL "), "istanbul")
        self.assertEqual(build.key("Istanbul"), "istanbul")
        self.assertEqual(build.key("Coca-Cola"), "cocacola")
        self.assertEqual(build.key("Côte d'Ivoire"), "cotedivoire")
        self.assertEqual(build.first_letter("Irak"), "ı")
        self.assertEqual(build.first_letter("İzmir"), "i")
    def test_cleaning(self):
        self.assertEqual(build.clean("Gelincik (balık)", "hayvan"), "Gelincik")
        self.assertIsNone(build.clean("Renault Latitude", "esya"))
        self.assertIsNone(build.clean("Silene nicaeensis", "bitki"))
        self.assertIsNone(build.clean("David Lawrence", "isim"))
        self.assertIsNone(build.clean("Yemek:Ispanak Çorbası", "yemek"))
        self.assertEqual(build.clean("Yenice, Çanakkale", "sehir"), "Yenice")
        self.assertEqual(build.clean("2001: Bir Uzay Destanı", "film"), None)  # cannot match any letter
        self.assertEqual(build.clean("Amiral battı (oyun)", "oyun"), "Amiral battı")

if __name__ == "__main__":
    unittest.main()
