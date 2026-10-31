import sys
import unittest
from pathlib import Path
sys.path.insert(0, str(Path(__file__).resolve().parents[1] / 'scripts'))
from import_frigor import prepare


class FrigorRosterTest(unittest.TestCase):
    def row(self, ci='0012345', name=None, internal=9, state='Habilitado'):
        return (ci, name, internal, state, 'Usuario', 1)

    def test_text_ci_is_identity_and_names_can_be_completed_without_new_person(self):
        before, _ = prepare([(5, self.row())])
        after, _ = prepare([(9, self.row(name='Nombre completo de prueba', internal=99))])
        self.assertEqual('FRIGOR-CI-0012345', before[0]['id'])
        self.assertEqual(before[0]['id'], after[0]['id'])
        self.assertEqual('0012345', after[0]['documento'])
        self.assertEqual('', after[0]['sap'])
        self.assertTrue(before[0]['nombrePendiente'])
        self.assertFalse(after[0]['nombrePendiente'])

    def test_duplicate_or_numeric_excel_id_rejected_instead_of_merging(self):
        with self.assertRaises(ValueError):
            prepare([(5, self.row()), (6, self.row(name='Different person'))])
        for value in [12345, ' 0012345', '=12345', '', None]:
            with self.assertRaises(ValueError):
                prepare([(5, self.row(ci=value))])

    def test_missing_names_and_unusual_id_are_not_invented_or_dropped(self):
        people, source = prepare([(5, self.row(ci='12345', name='12345 12345', state='Deshabilitado'))])
        self.assertEqual(1, len(people))
        self.assertTrue(people[0]['nombrePendiente'])
        self.assertTrue(source[0]['carnetPorRevisar'])
        self.assertFalse(people[0]['activo'])
        self.assertEqual('', people[0]['nombreCompleto'])
        self.assertIn('Nombre pendiente', people[0]['nombre'])


if __name__ == '__main__':
    unittest.main()
