"""Synthetic archive safety tests; real RC5-to-APK parity is checked separately."""
import importlib.util
from pathlib import Path
import tempfile
import unittest
import zipfile

spec=importlib.util.spec_from_file_location('restore_assets',Path(__file__).resolve().parents[1]/'scripts/restore-rc5-assets.py')
module=importlib.util.module_from_spec(spec);spec.loader.exec_module(module)

class RestoreTests(unittest.TestCase):
    def package(self,root,extras=None):
        archive=root/'rc5.apk'
        files={'assets/characters/evelyn/model.pmx':b'PMX model fixture','assets/characters/evelyn/textures.json':b'{"textures":{}}','res/drawable/myraa_icon.png':b'icon fixture'}
        files.update(extras or {})
        with zipfile.ZipFile(archive,'w') as z:
            for name,data in files.items():z.writestr(name,data)
        return archive

    def test_exact_and_repeatable_recovery(self):
        with tempfile.TemporaryDirectory() as folder:
            root=Path(folder);apk=self.package(root)
            first=module.restore(apk,root/'source');second=module.restore(apk,root/'source')
            self.assertEqual(first,second)
            self.assertFalse(first['signing_key_recovered'])
            self.assertEqual((root/'source/assets/characters/evelyn/model.pmx').read_bytes(),b'PMX model fixture')

    def test_existing_owner_assets_are_not_overwritten(self):
        with tempfile.TemporaryDirectory() as folder:
            root=Path(folder);apk=self.package(root);target=root/'source/assets/characters/evelyn/model.pmx'
            target.parent.mkdir(parents=True);target.write_bytes(b'owner model')
            with self.assertRaises(FileExistsError):module.restore(apk,root/'source')
            self.assertEqual(target.read_bytes(),b'owner model')
            self.assertFalse((root/'source/assets/branding/myraa_icon.png').exists())

    def test_traversal_rejected_before_writes(self):
        with tempfile.TemporaryDirectory() as folder:
            root=Path(folder);apk=self.package(root,{'assets/characters/evelyn/../escape':b'bad'})
            with self.assertRaises(ValueError):module.restore(apk,root/'source')
            self.assertFalse((root/'source').exists())

    def test_missing_model_refuses_incomplete_recovery(self):
        with tempfile.TemporaryDirectory() as folder:
            root=Path(folder);apk=root/'broken.apk'
            with zipfile.ZipFile(apk,'w') as z:z.writestr('res/drawable/myraa_icon.png',b'icon')
            with self.assertRaises(ValueError):module.restore(apk,root/'source')
            self.assertFalse((root/'source').exists())

if __name__=='__main__':unittest.main()
