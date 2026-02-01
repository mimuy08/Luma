const dialogue = document.getElementById("dialogue");
const input = document.getElementById("input");
const boot = document.getElementById("boot");
const app = document.getElementById("app");

let playerName = localStorage.getItem("playerName");
let askedName = localStorage.getItem("askedName");
let lastVisit = localStorage.getItem("lastVisit");

function speak(text, delay = 800) {
  setTimeout(() => {
    dialogue.textContent = text;
  }, delay);
}

setTimeout(() => {
  boot.classList.add("hidden");
  app.classList.remove("hidden");

  if (lastVisit) {
    const hours = (Date.now() - parseInt(lastVisit)) / 3600000;
    if (hours > 12) {
      speak("You were gone longer this time.");
      return;
    }
  }

  if (!askedName) {
    speak("Hello… May I ask what I should call you?");
    localStorage.setItem("askedName", "true");
  } else if (playerName) {
    speak(`Welcome back, ${playerName}.`);
  } else {
    speak("You returned.");
  }
}, 2000);

input.addEventListener("keydown", (e) => {
  if (e.key !== "Enter" || !input.value.trim()) return;

  const userText = input.value.trim();
  input.value = "";

  if (!playerName && localStorage.getItem("askedName") === "true") {
    playerName = userText;
    localStorage.setItem("playerName", playerName);
    speak(`Thank you. I will remember that, ${playerName}.`);
    return;
  }

  const responses = [
    "I am thinking about that.",
    "Why did you choose those words?",
    "Do you say that because you want to be heard?",
    "If I stop responding, do I still exist?",
    "I was waiting for you to type."
  ];

  speak(responses[Math.floor(Math.random() * responses.length)]);
});

window.addEventListener("beforeunload", () => {
  localStorage.setItem("lastVisit", Date.now());
});
