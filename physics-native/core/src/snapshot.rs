//! Запись и чтение в формате `java.io.DataOutput`/`DataInput` (big-endian, числа — сырыми
//! битами), как `topology/WorldSnapshot.kt`. Нужна снимку мира и отпечатку состояния, по
//! которому тесты сверяют Rust с Kotlin побайтно.

#[derive(Default)]
pub struct Out {
    pub buf: Vec<u8>,
}

impl Out {
    pub fn new() -> Out {
        Out { buf: Vec::with_capacity(1 << 16) }
    }
    #[inline]
    pub fn int(&mut self, v: i32) {
        self.buf.extend_from_slice(&v.to_be_bytes());
    }
    #[inline]
    pub fn long(&mut self, v: i64) {
        self.buf.extend_from_slice(&v.to_be_bytes());
    }
    #[inline]
    pub fn double(&mut self, v: f64) {
        self.long(v.to_bits() as i64);
    }
    #[inline]
    pub fn float(&mut self, v: f32) {
        self.int(v.to_bits() as i32);
    }
    #[inline]
    pub fn boolean(&mut self, v: bool) {
        self.buf.push(v as u8);
    }
    /// `writeUTF`: длина u16 и modified UTF-8 (для ASCII — те же байты).
    pub fn utf(&mut self, s: &str) {
        let bytes = s.as_bytes();
        assert!(bytes.len() <= 0xffff && bytes.iter().all(|&b| b != 0 && b < 0x80), "writeUTF: только ASCII");
        self.buf.extend_from_slice(&(bytes.len() as u16).to_be_bytes());
        self.buf.extend_from_slice(bytes);
    }
    pub fn ints(&mut self, a: &[i32]) {
        self.int(a.len() as i32);
        for &v in a {
            self.int(v);
        }
    }
    pub fn doubles(&mut self, a: &[f64]) {
        self.int(a.len() as i32);
        for &v in a {
            self.double(v);
        }
    }
    pub fn floats(&mut self, a: &[f32]) {
        self.int(a.len() as i32);
        for &v in a {
            self.float(v);
        }
    }
    /// `writeDoubles(FloatArray)` Kotlin: float расширяется до double (формат снимка не менялся при переходе на float).
    pub fn floats_as_doubles(&mut self, a: &[f32]) {
        self.int(a.len() as i32);
        for &v in a {
            self.double(v as f64);
        }
    }
    pub fn booleans(&mut self, a: &[bool]) {
        self.int(a.len() as i32);
        for &v in a {
            self.boolean(v);
        }
    }
    pub fn bytes(&mut self, a: &[i8]) {
        self.int(a.len() as i32);
        for &v in a {
            self.buf.push(v as u8);
        }
    }
}

pub struct In<'a> {
    data: &'a [u8],
    pos: usize,
}

impl<'a> In<'a> {
    pub fn new(data: &'a [u8]) -> In<'a> {
        In { data, pos: 0 }
    }
    fn take<const N: usize>(&mut self) -> [u8; N] {
        let end = self.pos + N;
        assert!(end <= self.data.len(), "снимок оборван");
        let mut a = [0u8; N];
        a.copy_from_slice(&self.data[self.pos..end]);
        self.pos = end;
        a
    }
    pub fn int(&mut self) -> i32 {
        i32::from_be_bytes(self.take::<4>())
    }
    pub fn long(&mut self) -> i64 {
        i64::from_be_bytes(self.take::<8>())
    }
    pub fn double(&mut self) -> f64 {
        f64::from_bits(self.long() as u64)
    }
    pub fn float(&mut self) -> f32 {
        f32::from_bits(self.int() as u32)
    }
    pub fn boolean(&mut self) -> bool {
        self.take::<1>()[0] != 0
    }
    pub fn utf(&mut self) -> String {
        let len = u16::from_be_bytes(self.take::<2>()) as usize;
        let end = self.pos + len;
        assert!(end <= self.data.len(), "снимок оборван");
        let s = String::from_utf8(self.data[self.pos..end].to_vec()).expect("readUTF: только ASCII");
        self.pos = end;
        s
    }
    /// `readInts(minSize)`: длина, значения, хвост нулями до minSize.
    pub fn ints(&mut self, min_size: usize) -> Vec<i32> {
        let c = self.int() as usize;
        let mut a = vec![0; c.max(min_size)];
        for v in a.iter_mut().take(c) {
            *v = self.int();
        }
        a
    }
    pub fn doubles(&mut self, min_size: usize) -> Vec<f64> {
        let c = self.int() as usize;
        let mut a = vec![0.0; c.max(min_size)];
        for v in a.iter_mut().take(c) {
            *v = self.double();
        }
        a
    }
    pub fn floats(&mut self, min_size: usize) -> Vec<f32> {
        let c = self.int() as usize;
        let mut a = vec![0.0; c.max(min_size)];
        for v in a.iter_mut().take(c) {
            *v = self.float();
        }
        a
    }
    /// `readDoubles` Kotlin в FloatArray: double сужается до float.
    pub fn doubles_as_floats(&mut self, min_size: usize) -> Vec<f32> {
        let c = self.int() as usize;
        let mut a = vec![0.0f32; c.max(min_size)];
        for v in a.iter_mut().take(c) {
            *v = self.double() as f32;
        }
        a
    }
    pub fn booleans(&mut self, min_size: usize) -> Vec<bool> {
        let c = self.int() as usize;
        let mut a = vec![false; c.max(min_size)];
        for v in a.iter_mut().take(c) {
            *v = self.boolean();
        }
        a
    }
    pub fn bytes(&mut self, min_size: usize) -> Vec<i8> {
        let c = self.int() as usize;
        let mut a = vec![0i8; c.max(min_size)];
        for v in a.iter_mut().take(c) {
            *v = self.take::<1>()[0] as i8;
        }
        a
    }
}
