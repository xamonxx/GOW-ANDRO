"""The launcher's string resources: every locale has every string, with the same format
arguments, and every value is one aapt2 accepts (an unescaped apostrophe fails the build).
Every R.string the code uses exists."""
import re
import unittest
import xml.etree.ElementTree as ElementTree
from pathlib import Path


ROOT = Path(__file__).resolve().parents[1]
RES = ROOT / 'android-launcher/app/src/main/res'
SOURCES = ROOT / 'android-launcher/app/src/main/java'
LOCALES = ('values', 'values-in', 'values-zh-rCN')
FORMAT = re.compile(r'%(\d+\$)?[sdf%]')


def _strings(folder):
    path = RES / folder / 'strings.xml'
    source = path.read_text(encoding='utf-8')
    root = ElementTree.fromstring(source.encode('utf-8'))
    raw = dict(re.findall(r'<string name="([^"]+)"[^>]*>(.*?)</string>', source, re.S))
    return root, raw


class StringResourceTests(unittest.TestCase):
    def test_every_locale_has_the_same_names(self):
        names = {}
        for folder in LOCALES:
            root, _ = _strings(folder)
            listed = [element.get('name') for element in root.iter('string')]
            self.assertEqual(len(listed), len(set(listed)), '%s has a duplicate name' % folder)
            names[folder] = set(listed)
        for folder in LOCALES[1:]:
            self.assertEqual(names['values'] - names[folder], set(), '%s is missing strings' % folder)
            self.assertEqual(names[folder] - names['values'], set(), '%s has strings English lacks' % folder)

    def test_format_arguments_match_english(self):
        _, english = _strings('values')
        for folder in LOCALES[1:]:
            _, translated = _strings(folder)
            for name, value in english.items():
                self.assertEqual(sorted(FORMAT.findall(value)), sorted(FORMAT.findall(translated[name])),
                                 '%s/%s changes the format arguments' % (folder, name))

    def test_apostrophes_and_quotes_are_escaped(self):
        for folder in LOCALES:
            _, raw = _strings(folder)
            for name, value in raw.items():
                self.assertIsNone(re.search(r"(^|[^\\])'", value), "%s/%s has an unescaped '" % (folder, name))
                self.assertIsNone(re.search(r'(^|[^\\])"', value), '%s/%s has an unescaped "' % (folder, name))

    def test_a_bare_percent_needs_formatted_false(self):
        for folder in LOCALES:
            root, _ = _strings(folder)
            for element in root.iter('string'):
                text = ''.join(element.itertext())
                bare = re.sub(r'%(\d+\$)?[sdf%]', '', text)
                if '%' in bare:
                    self.assertEqual(element.get('formatted'), 'false',
                                     '%s/%s has a bare %% without formatted="false"' % (folder, element.get('name')))

    def test_every_string_the_code_uses_exists(self):
        root, _ = _strings('values')
        names = {element.get('name') for element in root.iter('string')}
        used = set()
        for path in list(SOURCES.rglob('*.kt')) + list(SOURCES.rglob('*.java')):
            used |= set(re.findall(r'(?<![\w.])(?:com\.gow3\.android\.)?R\.string\.(\w+)', path.read_text(encoding='utf-8')))
        self.assertTrue(used)
        self.assertEqual(used - names, set())

    def test_indonesian_is_the_default_language(self):
        manager = (SOURCES / 'com/gow3/android/data/LanguageManager.kt').read_text(encoding='utf-8')
        self.assertIn('const val ID = "in"', manager)
        self.assertIn('.getString(KEY_LANGUAGE, ID) ?: ID', manager)
        activity = (SOURCES / 'ro/cobrabm/fexdroid/GameActivity.java').read_text(encoding='utf-8')
        self.assertIn('LanguageManager.wrap(base)', activity)


if __name__ == '__main__':
    unittest.main()
