use evdev::{
    uinput::{VirtualDevice, VirtualDeviceBuilder},
    AbsInfo, AbsoluteAxisType, AttributeSet, EventType, InputEvent, Key, RelativeAxisType,
    UinputAbsSetup,
};
use futures_util::StreamExt;
use serde::Deserialize;
use std::collections::{HashMap, HashSet};
use std::process::{Command, Stdio};
use std::sync::{Arc, Mutex};
use tokio::net::TcpListener;
use tokio_tungstenite::{accept_async, tungstenite::Message};

#[derive(Deserialize)]
#[serde(tag = "type")]
enum Msg {
    Move { dx: i32, dy: i32 },
    Click { button: String },
    Button { button: String, down: bool },
    Key { code: u16, down: bool },
    Scroll { dy: i32 },
    Pen {
        phase: String,
        #[serde(default)]
        x: f64,
        #[serde(default)]
        y: f64,
        #[serde(default)]
        p: f64,
    },
    Gesture {
        name: String,
        #[serde(default)]
        cmd: Option<String>,
    },
}

#[derive(Deserialize, Default)]
struct Config {
    #[serde(default)]
    gestures: HashMap<String, String>,
}

struct Pad {
    dev: VirtualDevice,
    tablet: VirtualDevice,
    held: HashSet<u16>,
    pen_in: bool,
}

impl Pad {
    fn key(&mut self, code: u16, down: bool) -> std::io::Result<()> {
        if down {
            self.held.insert(code);
        } else {
            self.held.remove(&code);
        }
        self.dev.emit(&[InputEvent::new(
            EventType::KEY,
            code,
            if down { 1 } else { 0 },
        )])
    }

    // Перо: координаты и нажим от 0 до 1, фаза "down", "move" или "up"
    fn pen(&mut self, x: f64, y: f64, p: f64, phase: &str) -> std::io::Result<()> {
        if phase == "up" {
            if self.pen_in {
                self.pen_in = false;
                self.tablet.emit(&[
                    InputEvent::new(EventType::ABSOLUTE, AbsoluteAxisType::ABS_PRESSURE.0, 0),
                    InputEvent::new(EventType::KEY, Key::BTN_TOUCH.code(), 0),
                    InputEvent::new(EventType::KEY, Key::BTN_TOOL_PEN.code(), 0),
                ])?;
            }
            return Ok(());
        }
        let xi = (x.clamp(0.0, 1.0) * 32767.0) as i32;
        let yi = (y.clamp(0.0, 1.0) * 32767.0) as i32;
        let pi = (p.clamp(0.0, 1.0) * 4095.0) as i32;
        let mut ev = Vec::with_capacity(5);
        if !self.pen_in {
            self.pen_in = true;
            ev.push(InputEvent::new(EventType::KEY, Key::BTN_TOOL_PEN.code(), 1));
        }
        ev.push(InputEvent::new(EventType::ABSOLUTE, AbsoluteAxisType::ABS_X.0, xi));
        ev.push(InputEvent::new(EventType::ABSOLUTE, AbsoluteAxisType::ABS_Y.0, yi));
        ev.push(InputEvent::new(EventType::ABSOLUTE, AbsoluteAxisType::ABS_PRESSURE.0, pi));
        ev.push(InputEvent::new(EventType::KEY, Key::BTN_TOUCH.code(), 1));
        self.tablet.emit(&ev)
    }

    // Отпускаем всё зажатое, чтобы клавиши и перо не залипали при обрыве связи
    fn release_all(&mut self) {
        let held: Vec<u16> = self.held.drain().collect();
        for code in held {
            let _ = self
                .dev
                .emit(&[InputEvent::new(EventType::KEY, code, 0)]);
        }
        let _ = self.pen(0.0, 0.0, 0.0, "up");
    }
}

fn button_code(name: &str) -> u16 {
    match name {
        "right" => Key::BTN_RIGHT.code(),
        "middle" => Key::BTN_MIDDLE.code(),
        _ => Key::BTN_LEFT.code(),
    }
}

fn config_path() -> std::path::PathBuf {
    let home = std::env::var("HOME").unwrap_or_default();
    std::path::PathBuf::from(home).join(".config/trackpad/gestures.toml")
}

fn run_gesture(name: &str, cmd: Option<String>) {
    // Команда из сообщения важнее; если её нет, берём из gestures.toml
    let cmd = match cmd.filter(|c| !c.trim().is_empty()) {
        Some(c) => Some(c),
        None => {
            let cfg: Config = std::fs::read_to_string(config_path())
                .ok()
                .and_then(|s| toml::from_str(&s).ok())
                .unwrap_or_default();
            cfg.gestures.get(name).cloned()
        }
    };

    match cmd {
        Some(cmd) => {
            println!("Жест «{name}»: запускаю {cmd}");
            match Command::new("sh")
                .arg("-c")
                .arg(&cmd)
                .stdin(Stdio::null())
                .stdout(Stdio::null())
                .stderr(Stdio::null())
                .spawn()
            {
                Ok(mut child) => {
                    std::thread::spawn(move || {
                        let _ = child.wait();
                    });
                }
                Err(e) => println!("Не удалось запустить: {e}"),
            }
        }
        None => println!("Для жеста «{name}» нет команды"),
    }
}

fn rel(axis: RelativeAxisType, v: i32) -> InputEvent {
    InputEvent::new(EventType::RELATIVE, axis.0, v)
}

fn handle(pad: &mut Pad, msg: Msg) -> std::io::Result<()> {
    match msg {
        Msg::Move { dx, dy } => pad.dev.emit(&[
            rel(RelativeAxisType::REL_X, dx),
            rel(RelativeAxisType::REL_Y, dy),
        ]),
        Msg::Click { button } => {
            let code = button_code(&button);
            pad.key(code, true)?;
            pad.key(code, false)
        }
        Msg::Button { button, down } => pad.key(button_code(&button), down),
        Msg::Key { code, down } => pad.key(code, down),
        Msg::Scroll { dy } => pad.dev.emit(&[rel(RelativeAxisType::REL_WHEEL, dy)]),
        Msg::Pen { phase, x, y, p } => pad.pen(x, y, p, &phase),
        Msg::Gesture { name, cmd } => {
            run_gesture(&name, cmd);
            Ok(())
        }
    }
}

#[tokio::main]
async fn main() -> std::io::Result<()> {
    // Виртуальная мышь и клавиатура
    let mut axes = AttributeSet::<RelativeAxisType>::new();
    axes.insert(RelativeAxisType::REL_X);
    axes.insert(RelativeAxisType::REL_Y);
    axes.insert(RelativeAxisType::REL_WHEEL);

    let mut keys = AttributeSet::<Key>::new();
    keys.insert(Key::BTN_LEFT);
    keys.insert(Key::BTN_RIGHT);
    keys.insert(Key::BTN_MIDDLE);
    // Все обычные клавиши клавиатуры (коды 1..=248, включая Super)
    for code in 1u16..=248 {
        keys.insert(Key::new(code));
    }

    let dev = VirtualDeviceBuilder::new()?
        .name("phone-trackpad")
        .with_relative_axes(&axes)?
        .with_keys(&keys)?
        .build()?;

    // Виртуальный графический планшет с абсолютными координатами и нажимом
    let abs_x = UinputAbsSetup::new(AbsoluteAxisType::ABS_X, AbsInfo::new(0, 0, 32767, 0, 0, 200));
    let abs_y = UinputAbsSetup::new(AbsoluteAxisType::ABS_Y, AbsInfo::new(0, 0, 32767, 0, 0, 200));
    let abs_p = UinputAbsSetup::new(
        AbsoluteAxisType::ABS_PRESSURE,
        AbsInfo::new(0, 0, 4095, 0, 0, 0),
    );
    let mut tkeys = AttributeSet::<Key>::new();
    tkeys.insert(Key::BTN_TOOL_PEN);
    tkeys.insert(Key::BTN_TOUCH);
    tkeys.insert(Key::BTN_STYLUS);

    let tablet = VirtualDeviceBuilder::new()?
        .name("phone-tablet")
        .with_keys(&tkeys)?
        .with_absolute_axis(&abs_x)?
        .with_absolute_axis(&abs_y)?
        .with_absolute_axis(&abs_p)?
        .build()?;

    let pad = Arc::new(Mutex::new(Pad {
        dev,
        tablet,
        held: HashSet::new(),
        pen_in: false,
    }));

    let listener = TcpListener::bind("127.0.0.1:9000").await?;
    println!("Слушаю 127.0.0.1:9000");

    loop {
        let (stream, addr) = listener.accept().await?;
        println!("TCP-подключение от {addr}");
        let pad = pad.clone();
        tokio::spawn(async move {
            let ws = match accept_async(stream).await {
                Ok(ws) => ws,
                Err(e) => {
                    println!("Ошибка рукопожатия: {e}");
                    return;
                }
            };
            println!("WebSocket открыт");
            let (_, mut read) = ws.split();
            while let Some(item) = read.next().await {
                match item {
                    Ok(Message::Text(text)) => match serde_json::from_str::<Msg>(&text) {
                        Ok(msg) => {
                            if let Err(e) = handle(&mut pad.lock().unwrap(), msg) {
                                println!("Ошибка emit: {e}");
                            }
                        }
                        Err(e) => println!("Не разобрал JSON: {e}"),
                    },
                    Ok(_) => {}
                    Err(e) => {
                        println!("Ошибка чтения: {e}");
                        break;
                    }
                }
            }
            pad.lock().unwrap().release_all();
            println!("Соединение закрыто");
        });
    }
}
